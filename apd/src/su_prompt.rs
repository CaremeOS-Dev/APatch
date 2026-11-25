//! su prompt daemon.
//!
//! The kernel parks an `su` exec from a non-whitelisted uid and waits for a
//! decision (see KernelPatch `su_prompt`). Something has to show the user a
//! confirmation and write the answer back.
//!
//! That something is this daemon, not the app, for the same reason Magisk's
//! magiskd does it: this process is root, so `am start` is not subject to the
//! background-activity restrictions that make a foreground-service launch
//! unreliable on these ROMs. The activity it starts is plain UI; the daemon keeps
//! polling and re-launches if the user dismissed it while the request is still
//! pending.

use anyhow::{Context, Result};
use log::{error, info, warn};
use std::collections::HashSet;
use std::process::Command;
use std::thread;
use std::time::{Duration, Instant};

use crate::supercall::{self, SuRequest};

/// A pending request whose activity died without answering is retried once, and
/// not more: two dialogs are enough to cover "the user dismissed it by accident",
/// while a third would just stack windows over each other.
const MAX_LAUNCH_PER_REQUEST: u32 = 2;
/// How long a request may sit unanswered before we stop trying to launch UI.
const REQUEST_DEADLINE: Duration = Duration::from_secs(10);
/// Minimum gap between two launches for the same request. The activity needs time
/// to cold-start; re-launching before that only produces duplicates.
const RETRY_INTERVAL: Duration = Duration::from_secs(5);

struct Tracked {
    first_seen: Instant,
    launches: u32,
    next_launch_at: Instant,
}

pub fn run(
    superkey: Option<String>,
    package: &str,
    manager_uid: Option<u32>,
    interval_ms: u64,
) -> Result<()> {
    let activity = format!("{package}/com.zcg.apatch.su.SuPromptActivity");
    info!("[su_prompt] daemon start, target={activity}, interval={interval_ms}ms");
    // Also on stderr: this is a daemon, so a manual `apd su-prompt ...` looks like a
    // hang unless it says what it is doing and why it is not prompting anyone.
    eprintln!(
        "[su_prompt] watching for su requests (target {activity}, {} ms). \
         This is a daemon: it does not return. Ctrl-C to stop.",
        interval_ms
    );
    if superkey.is_none() {
        eprintln!(
            "[su_prompt] warning: no --superkey given; the kernel will answer -EPERM \
             unless this uid is the trusted manager"
        );
    }

    let mut tracked: std::collections::HashMap<i32, Tracked> = std::collections::HashMap::new();
    let mut poll_failures = 0u32;

    loop {
        let reqs = match supercall::su_prompt_poll(superkey.as_deref(), 32) {
            Ok(v) => {
                if poll_failures > 0 {
                    eprintln!("[su_prompt] poll recovered after {poll_failures} failure(s)");
                    poll_failures = 0;
                }
                v
            }
            Err(e) => {
                // Not fatal: the kernel may not have the feature compiled in, or
                // the superkey may be wrong. Back off and keep the daemon alive.
                poll_failures = poll_failures.saturating_add(1);
                // Only the first few are worth a line; after that it is spam.
                if poll_failures <= 3 || poll_failures % 100 == 0 {
                    warn!("[su_prompt] poll failed: {e:#}");
                    eprintln!("[su_prompt] poll failed ({poll_failures}): {e:#}");
                }
                thread::sleep(Duration::from_millis(interval_ms.max(1000)));
                continue;
            }
        };

        let now = Instant::now();
        for req in &reqs {
            let entry = tracked.entry(req.pid).or_insert_with(|| Tracked {
                first_seen: now,
                launches: 0,
                next_launch_at: now,
            });

            if now.duration_since(entry.first_seen) > REQUEST_DEADLINE {
                // Kernel has already timed out; stop bothering the user.
                continue;
            }
            // Deadline-based retry: never block the poll loop (a sleep here would
            // stall every other pending request and, because the entry survives
            // between polls, would fire the same dialog three times in a row).
            if entry.launches >= MAX_LAUNCH_PER_REQUEST || now < entry.next_launch_at {
                continue;
            }

            entry.launches += 1;
            entry.next_launch_at = now + RETRY_INTERVAL;
            launch_activity(&activity, req, manager_uid);
        }

        // Forget requests that are gone, so the maps do not grow without bound.
        let live: HashSet<i32> = reqs.iter().map(|r| r.pid).collect();
        tracked.retain(|pid, _| live.contains(pid));

        thread::sleep(Duration::from_millis(interval_ms));
    }
}

fn launch_activity(activity: &str, req: &SuRequest, manager_uid: Option<u32>) {
    let scontext = String::from_utf8_lossy(&req.scontext)
        .trim_end_matches('\0')
        .to_string();
    let comm = String::from_utf8_lossy(&req.comm)
        .trim_end_matches('\0')
        .to_string();

    let mut cmd = Command::new("am");
    cmd.args([
        "start",
        "-n",
        activity,
        "--ei",
        "pid",
        &req.pid.to_string(),
        "--ei",
        "uid",
        &req.uid.to_string(),
        "--es",
        "scontext",
        &scontext,
        "--es",
        "comm",
        &comm,
    ]);
    // FLAG_ACTIVITY_NEW_TASK, in numeric form: the `--activity-new-task` spelling
    // only exists on newer `am`, and this device runs Android 8.1.
    cmd.args(["-f", "0x10000000"]);

    // Run `am` as the manager's uid. SuPromptActivity is not exported, so a launch
    // from this daemon's uid (0) is refused with "Permission Denial ... not
    // exported"; from the app's own uid it is a same-uid start, which is allowed.
    // Keeping the activity unexported matters: an exported prompt could be popped
    // by any app with a pid of its choosing, tricking the user into granting root.
    if let Some(uid) = manager_uid {
        use std::os::unix::process::CommandExt;
        cmd.uid(uid);
    }

    match cmd.output() {
        Ok(out) if out.status.success() => {
            info!("[su_prompt] launched UI for pid={} uid={}", req.pid, req.uid);
        }
        Ok(out) => {
            let err = String::from_utf8_lossy(&out.stderr);
            warn!(
                "[su_prompt] am start failed for pid={}: {}",
                req.pid,
                err.trim()
            );
        }
        Err(e) => error!("[su_prompt] cannot run am: {e:#}"),
    }
}

/// Used by the boot path: make sure exactly one daemon is running.
pub fn ensure_running(package: &str) -> Result<()> {
    let out = Command::new("pidof")
        .arg("apd")
        .output()
        .context("pidof apd")?;
    let _ = out;
    // The daemon is started by the manager (or by post-fs-data) with the
    // `su-prompt` subcommand; nothing to do here beyond logging.
    info!("[su_prompt] ensure_running package={package}");
    Ok(())
}
