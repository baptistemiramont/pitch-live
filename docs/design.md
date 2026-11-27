# Pitch Live: design

Replay a real football match at 25 frames per second, stream it live from a Java server, and display it in 2D in a browser. This document describes the first version: the problem, the architecture, the decisions, and what the measurements taught. The [README](../README.md) covers running it, the messages and the measurements themselves.

## The problem

Display live, for analysts, the player positions produced by a tracking system: a stream at 25 frames per second, drawn in 2D, with clients of varying speed.

Two things matter. Server side, concurrency: a service that runs continuously and handles connections in parallel, where a slow client must not hold the others back. Browser side, smooth rendering of a high-frequency stream.

## Goals and limits of the first version

- **Goals**: a Java server that replays a match and broadcasts it to several browsers; a client that displays it smoothly; an end-to-end latency measurement.
- **Not yet**: authentication, persistence, deployment, several matches at once, mobile, a rendering engine. The [roadmap](../README.md#roadmap) says what comes next.

## The data

Metrica Sports' sample data, published on GitHub (`metrica-sports/sample-data`): real, anonymised matches, with the position of every player and of the ball, 25 times per second, and the events of the match. Match 1 comes as three CSV files: tracking for each team, and events. Coordinates are not in metres: the server converts them, following Laurie Shaw's tutorials. The data is not versioned in the repository: a script downloads it, and the README credits the source, as Metrica asks.

## The architecture, in broad strokes

```
CSV files ──► Java server: replays the match, one frame every 40 ms
                  │
                  └──► WebSocket broadcast ──► browsers: receive, then 2D rendering
```

Two principles guide the design:

1. **Server side, a slow client slows nobody down.** It gets the latest available frame, not a growing backlog.
2. **Browser side, receiving and drawing are separate.** Messages arrive when they arrive; the screen redraws at its own pace, from the latest frame received.

## The monorepo

| Folder | Content |
| --- | --- |
| `server/` | The Spring Boot server, with Maven |
| `client/` | The Vite and TypeScript client, no framework, drawn in a canvas |
| `docs/` | This document and the demo recording |
| `scripts/` | The data download |
| `data/` | Metrica's CSV files, ignored by git |

At the root, a README and a Makefile to fetch the data, run the server, the client and the tests. No monorepo tool: two independent projects, linked by a documented message contract.

## The contract between server and client

Five messages, listed in the [README](../README.md#messages-between-the-server-and-the-browser) and mirrored by hand in `Protocol.java` and `protocol.ts`:

- **on connection**, the server presents the match (pitch dimensions, players and teams) and the events so far;
- **on every frame**, it sends the positions in metres, with the send time to measure latency, and the event log again whenever it changes;
- **the other way round**, the client asks for a rate, or restarts the match.

Every message carries a version number. A breaking change creates a new version, and the server accepts both during the transition. Frames can also travel in binary, chosen by each client when it connects; the presentation stays in JSON.

## Threads and shared state

Three kinds of threads run in the server:

- **the clock thread** (`replay-clock`), a platform thread that runs all the time: it reads the next line of each file, builds the frame and publishes it, every 40 ms. `scheduleAtFixedRate` does not drift, and a catch-all `try` keeps the replay from dying silently;
- **Tomcat's threads**, for connections and incoming messages;
- **one virtual thread per client**, which waits on its mailbox and sends at its client's pace.

They share the client map (`ConcurrentHashMap`), the latest frame (`AtomicReference`), the presentation and each client's rate (`volatile`), and the mailboxes (one-slot `ArrayBlockingQueue`). Frames are immutable records, their lists copied with `List.copyOf`, so they cross threads without locking. The file readers are confined to the clock thread: a restart is handed over to that thread (`clock.execute(...)`) instead of taking a lock.

## Decisions

| Decision | Why | What it costs |
| --- | --- | --- |
| Replay a CSV rather than use a public API | Control the rate and work on the server side | It is not truly live |
| WebSocket rather than SSE | The client can ask for another rate, and binary remains possible | Reconnection is ours to handle |
| Raw WebSocket, no STOMP | Keep control over what goes to each client, and at what rate | Routing and subscriptions are ours to write |
| JSON first, binary as an option | Readable, and binary only saves about 30% once compression is counted | Heavier before compression |
| Positions in metres, converted by the server, origin at the top left and y axis pointing down | The contract does not depend on the data source, and the coordinate system is the canvas's | The server knows Metrica's format |
| Positions rounded to the centimetre | Tracking is not more accurate, and a frame weighs 17% less | None on screen |
| Full roster on connection, only positions in each frame | A viewer who joins mid-match has everything, without a fourth message type | A frame means nothing without the presentation |
| Player off the pitch: absent from the frame; ball missing: `null` | We do not make up data, and we do not infer an event (substitution, red card) from a gap in tracking | The client handles missing players and a missing ball |
| Keep only the latest frame for each client, in a one-slot mailbox | A slow client stays live; an unbounded queue would make it fall behind and fill the memory | It skips frames |
| One virtual thread per client | Its slowness only blocks itself; a thousand clients cost a thousand virtual threads, carried by a few OS threads | Each client holds a thread, even while it waits |
| Each frame encoded once, in both formats | 25 encodings per second, whatever the number of clients | Every client of a format gets the same bytes |
| Goals and cards sent as the whole log, versioned, carried by every frame | A goal must never be lost: a client that skips frames or joins late still gets every goal | The log grows with the match: fine for goals and cards, not for every pass |
| Restart handed over to the clock thread | The readers stay confined to that thread: no lock, and the rewind always happens between two ticks | A restart waits for the current tick to end |
| Restart shared by every viewer | The replay is one shared stream, like a live broadcast | No personal rewind (see the roadmap) |
| Receiving and drawing kept separate, canvas without a framework | Messages in a burst only cost one assignment each; 23 dots can be drawn without heavy tooling, where React would re-render components 25 times per second | To revisit with trajectories, zones or heatmaps |
| No monorepo tool | Two simple projects | The contract is kept in sync by hand between Java and TypeScript |

## What the measurements taught

The figures are in the [README](../README.md#what-the-measurements-show). Three lessons:

1. **The one-slot mailbox only protects against sends that block**: a slow network, a client that stops reading. A page that is too slow to process what it receives lets frames pile up in the OS and browser buffers, several megabytes, before the server feels anything. The remedy is flow control in the protocol: the client acknowledges every frame, and the server never has more than one in flight towards it.
2. **Binary only saves about 30% once compression is counted.** `permessage-deflate`, negotiated by default, brings a JSON frame from 1,240 bytes down to about 176, against 126 for binary. The real benefit of binary would be to save the compression work, which runs once per client and per frame, provided compression is turned off for those clients. Tomcat compresses binary messages too.
3. **Latency is measured between two clocks.** Even on one machine, the server's (the system clock, adjusted by NTP) and the browser's (a monotonic timer anchored at page load) only agree to within about a millisecond: the panel once showed -0.7 ms. Across two machines, their offset adds up to the measurement; it can be estimated with a ping/pong round trip, assuming half of it is the one-way trip.

## Design questions, answered

**How to replay two files in parallel without loading the whole match into memory?**
Two file readers advanced together, one line each, checking that the frame numbers match. The match would fit in memory (145,006 frames, about 50 MB as `double`s), but a real live stream never ends: it might as well be read like a stream.

**How to keep a slow client from delaying the others? What does Spring's decorator not solve?**
A one-slot mailbox and a sender thread per client: the clock drops the frame and moves on. Spring's `ConcurrentWebSocketSessionDecorator` buffers messages while a send is in progress, with time and size limits, but the thread that gets the lock does the send itself: if that is the clock, it stays blocked on the slow client. Neither the decorator nor the mailbox solves the case of a slow page behind full buffers.

**How does a viewer who joins mid-match see the state of the pitch right away?**
They receive the presentation, which holds the full roster, then the latest published frame, dropped into their mailbox even before they are registered.

**How to adapt the rate to each client?**
The client sends a `rate` message; its sender thread sleeps between two sends, and on wake-up takes the latest frame. The server forgets everything on disconnection, so the client asks for its rate again when it reconnects.

**How do the tracking and the events stay in sync?**
Metrica aligned its event data on the tracking frame numbers, so the clock releases each event at its frame. Live, both streams would carry timestamps from a common clock, and the faster one would be buffered until the slower one catches up.

**How to draw 25 times per second without going through a framework's state?**
Plain variables, replaced on receipt, and a `requestAnimationFrame` loop that only redraws when something is new. Smoothing draws an in-between state at every refresh, at the cost of one frame of delay.

**What changes with a live tracking source instead of a CSV?**
Only the reader, since the contract is in metres. But live data is noisy: players lost for a few frames, hence the rule of not inferring events from a gap. With full skeletons at 50 frames per second, message size would really matter: Protobuf, and compression turned off.

**How would it scale to hundreds of viewers, or several matches?**
By separating production from fan-out: the replay, or the tracking models, publish to a message bus, several WebSocket servers subscribe and each broadcasts to its own clients. One clock, one presentation and one latest frame per match.

**How do we know it works?**
A test with a fake slow client proves that publishing never blocks and that the slow client ends on the latest frame; the rest was measured in a real browser.

## Sources

- [Metrica Sports: sample data](https://github.com/metrica-sports/sample-data)
- [LaurieOnTracking (Laurie Shaw)](https://www.github.com/Friends-of-Tracking-Data-FoTD/LaurieOnTracking), for reading the data and converting it to metres
