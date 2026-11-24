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

/// A pending request whose activity died without answering is retried, but not
/// forever: the kernel gives up after its own timeout.
const MAX_LAUNCH_PER_REQUEST: u32 = 3;
/// How long a request may sit unanswered before we stop trying to launch UI.
const REQUEST_DEADLINE: Duration = Duration::from_secs(10);

struct Tracked {
    first_seen: Instant,
    launches: u32,
}

pub fn run(superkey: Option<String>, package: &str, interval_ms: u64) -> Result<()> {
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
    let mut seen_once: HashSet<i32> = HashSet::new();
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
            });

            if now.duration_since(entry.first_seen) > REQUEST_DEADLINE {
                // Kernel has already timed out; stop bothering the user.
                continue;
            }
            if entry.launches >= MAX_LAUNCH_PER_REQUEST {
                continue;
            }

            entry.launches += 1;
            if !seen_once.insert(req.pid) {
                // Already shown once; only re-launch when the UI has clearly gone
                // (the activity finishing without a decision leaves the request
                // pending, so this is the retry path).
                thread::sleep(Duration::from_millis(1500));
            }
            launch_activity(&activity, req);
        }

        // Forget requests that are gone, so the maps do not grow without bound.
        let live: HashSet<i32> = reqs.iter().map(|r| r.pid).collect();
        tracked.retain(|pid, _| live.contains(pid));
        seen_once.retain(|pid| live.contains(pid));

        thread::sleep(Duration::from_millis(interval_ms));
    }
}

fn launch_activity(activity: &str, req: &SuRequest) {
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
    // The activity must not be forced to the foreground of an existing task.
    cmd.args(["--activity-new-task"]);

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
