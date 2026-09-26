# ADR 0011: The App runs each scan in a process of its own

Date: 2026-09-26
Status: accepted

## Context

The App's worker scanned repositories inside the web server's JVM. A scan reads files someone
else chose and parses them with tree-sitter, a native library reached through JNI. Three things
followed from sharing the process:

1. **A crash in the parser cannot be caught.** A segmentation fault in native code ends the JVM,
   and with it webhooks, the REST API, sign-in and the site's backend, not just the scan.
2. **A runaway scan shares the server's heap.** The whole-scan limits (files, bytes, time) are
   checked between files, so they bound a well-behaved scan, but nothing bounded one that was
   not, and every worker thread's files counted against the server's memory.
3. **A run the crash interrupted stayed `running` forever.** The worker claims only `queued`
   runs, so nothing ever finished or retried it.

The scan must also keep giving exactly the same inventory and findings, and the App's image is
built outside this repository, so the fix should need nothing new installed in it.

## Decision

**Each scan runs in a child JVM started from the App's own classes, with a heap cap, a
wall-clock limit and an empty environment.** `docswatcher.scan.isolation` selects it:
`process` (the default) or `in-process`.

- **One scan function, two places to run it.** `ScanJob` is the only code that reads a checkout:
  own records merged, the three layers, matching, written as one JSON document shaped like
  `ScanEngine.OwnScan`. `EngineScanEngine` runs it in the server; `ProcessScanEngine` runs it in
  a child through the `ScanChild` entry point and parses the document with the same method. The
  two modes cannot drift apart, and a test scans every fixture both ways and compares the results.
- **No new binary.** The child is started from whatever started the server: `java -cp <classpath>
  ScanChild` on a plain classpath; the same jar or launcher class with a marker argument when the
  server runs from the Spring Boot jar, which `DocsWatcherApplication.main` hands to `ScanChild`
  before Spring starts; the same executable for a native image. The image needs no change.
- **Limits.** `-Xmx` from `docswatcher.scan.process.heap-mb` (1024) with
  `-XX:+ExitOnOutOfMemoryError`, and a timeout of `max-seconds` plus 60 s, after which the child
  and anything it started are killed. The engine's own limits still apply inside, so a large
  repository still ends as an incomplete scan rather than a killed one.
- **Nothing to steal.** The child gets `PATH`, `LANG`, `LC_ALL`, `TZ` and `TMPDIR` and nothing
  else: no database password, App key or API token reaches the process that parses outsiders'
  code. Its arguments are paths and limits; it opens no connection.
- **Failure is a failed run, in words.** Exit 0 with a document is a result. Anything else fails
  the run with a sentence: `The scan process crashed (signal 11)`, `The scan ran out of memory
  (its process may use 1024 MB)`, `The scan took longer than 660 s and its process was stopped`,
  or `The scan failed: <the exception>`. The last 16 KB of the child's output go to the log.
- **A startup probe.** At startup the App starts one child that scans an empty directory. If that
  fails, it logs an error and runs scans in the server, because failing every scan would be
  worse than the risk this decision removes. `isolation: in-process` states that choice outright.
- **Abandoned runs are reclaimed.** At startup and every five minutes, runs `running` longer
  than the scan timeout plus two clone timeouts plus ten minutes are failed with an error
  starting `abandoned: ` and queued again once. A retry is recognised by the original's
  `finished_at` equalling its `created_at` (both are written in one transaction), so no column
  is added and a run that crashes the App every time is tried twice, not forever.

## Consequences

- **Each scan pays a process start.** Measured on the development machine (8 cores, other builds
  running): 0.6 to 1.5 s to start a JVM, load the knowledge base and scan nothing, from a plain
  classpath and through the Spring Boot jar alike; about 0.6 s when the machine is quiet. The
  startup probe logs the figure on each start of the App. Scans are triggered by pushes
  and take seconds to minutes, so this is small beside the clone. C1-only compilation
  (`-XX:TieredStopAtLevel=1`) and the serial collector keep it down; a scan long enough to want
  C2 can have it through `docswatcher.scan.process.jvm-options`.
- **Memory is budgeted per scan.** Each worker thread can hold one child of up to `heap-mb` plus
  the JVM's own overhead, so the container needs room for `threads` children beside the server;
  the server no longer needs heap for the files of its scans. Native memory used by the parser is
  not covered by `-Xmx`; the container's limit remains the ceiling for that.
- **The configured limits now reach every scan.** Before, a scan with own records used the
  engine's default limits whatever `docswatcher.scan` said. The defaults are the same values, so
  nothing changes unless they were set.
- A run the reclaim fails and queues again may, very rarely, still be running (a scan slower
  than the reclaim margin). It then finishes and records `done` over `failed`, and the retry
  repeats work that is idempotent.
- The tests replace the engine with a fake, so `in-process` is set for them; the scan process
  has its own tests, which start real children, including ones that crash, hang, exhaust their
  heap and print without end.

## Rejected alternatives

- **The CLI binary as the child.** It starts faster, but its JSON is the inventory alone: the
  App also needs the own records' providers, change records, problems and warnings, and would
  have to repeat the merge in the server to match. It would also need installing in the image and
  keeping in step with the App's engine version. The App's own classes are the same engine by
  construction.
- **A long-lived scan server process.** It would save the start cost but share a heap between
  scans again, and need a protocol, supervision and restarts: the problem moved, not removed.
- **A container per scan.** The strongest isolation, but it needs a container runtime inside the
  App's container or a socket to the host's, which is a larger hole than the one it closes.
- **Catching the crash in the server.** Signals from native code cannot be recovered from in the
  JVM; a handler would leave the process in an undefined state.
