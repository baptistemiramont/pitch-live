# pitch-live: design

Replay a real football match at 25 frames per second, stream it live from a Java server, and display it in 2D in a browser.

## Context

**The problem.** Display live, for analysts, the player positions produced by a tracking system: a stream at 25 frames per second, drawn in 2D, with clients of varying speed.

**What matters.** Server side, concurrency: a service that runs continuously and handles connections in parallel. Browser side, smooth rendering of a high-frequency stream.

## Goals and limits

- **Goals**: a Java server that replays a match and broadcasts it to several browsers; a client that displays it smoothly; an end-to-end latency measurement.
- **Out of scope**: authentication, persistence, deployment, several matches at once, mobile, React, PixiJS.

## The data

Metrica Sports' sample data, published on GitHub (`metrica-sports/sample-data`): real, anonymised matches, with the position of every player and of the ball, 25 times per second. Matches 1 and 2 are in CSV, one file per team. Coordinates are not in metres and must be converted; Laurie Shaw's tutorials show how. The data is not versioned in the repository: a script downloads it, and the README credits the source, as Metrica asks.

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
| `docs/` | This document, and the decisions if they grow |
| `scripts/` | The data download |
| `data/` | Metrica's CSV files, ignored by git |

At the root, a README and a Makefile to fetch the data, run the server, the client and the tests. No monorepo tool: two independent projects, linked by a documented message contract.

## The contract between server and client

Three messages, in JSON to begin with:

- **on connection**, the server presents the match: pitch dimensions, players and teams;
- **on every frame**, it sends the positions, with the send time to measure latency;
- **the other way round**, the client can ask for a lower rate.

Every message carries a version number. A breaking change creates a new version, and the server accepts both during the transition.

## Questions to solve along the way

- How to replay two files in parallel without loading the whole match into memory?
- Which threads run in the server, and what state do they share?
- How to keep a slow client from delaying the others? Leads: Java's concurrent collections and virtual threads, Spring's WebSocket session decorator, and what it does not solve.
- How does an analyst who joins mid-match see the state of the pitch right away?
- How to adapt the rate to each client?
- How to measure latency, and what is the limit of that measurement?
- How to draw 25 times per second without going through a framework's state?

## Decisions

| Decision | Why | What it costs |
| --- | --- | --- |
| Replay a CSV rather than use a public API | Control the rate and work on the server side | It is not truly live |
| WebSocket rather than SSE | The client can ask for another rate, and binary remains possible | Reconnection is ours to handle |
| JSON first | Readable in the browser's developer tools | Heavier; binary will wait until the size has been measured |
| Positions in metres, converted by the server, origin at the top left and y axis pointing down | The contract does not depend on the data source, and the coordinate system is the canvas's | The server knows Metrica's format |
| Keep only the latest frame for each client | A slow client stays live instead of falling behind | It skips frames |
| Canvas without a rendering engine or React | 23 dots can be drawn without heavy tooling | To revisit with trajectories, zones or heatmaps |
| No monorepo tool | Two simple projects | The contract is kept in sync by hand between Java and TypeScript |

## Milestones

| Step | Expected result |
| --- | --- |
| 0. Setup | Both projects start, the data is there |
| 1. Replay and broadcast | The browser receives frames at 25 per second |
| 2. The pitch | Players and ball move on screen |
| 3. Slow clients | A rate per client; two tabs at different paces |
| 4. Latency | End-to-end latency is displayed |
| 5. Going further | Simulate a real slow client, smooth the movement, switch to binary |

## Sources

- [Metrica Sports: sample data](https://github.com/metrica-sports/sample-data)
- [LaurieOnTracking (Laurie Shaw)](https://www.github.com/Friends-of-Tracking-Data-FoTD/LaurieOnTracking), for reading the data and converting it to metres
