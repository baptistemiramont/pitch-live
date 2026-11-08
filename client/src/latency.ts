/**
 * The current time in milliseconds since 1970, with decimals: the same scale as the
 * server's sentAt. Date.now() stops at the millisecond, too coarse for latencies of
 * about a millisecond on a local machine.
 */
export function now() {
  return performance.timeOrigin + performance.now()
}

/**
 * Accumulates latency measurements over a window (one second in the panel), then
 * returns their average and maximum and starts over.
 *
 * The limit of the measurement: sentAt comes from the server's clock, the receipt time
 * from the browser's. On a single machine, it is the same clock and the measurement is
 * accurate. Across two machines, the offset between their clocks (a few milliseconds at
 * best with NTP) adds up to the measurement, and can exceed the latency itself. That
 * offset would then have to be estimated, for instance with a ping/pong round trip,
 * assuming half of it is the one-way trip.
 */
export class LatencyWindow {
  private sum = 0
  private count = 0
  private max = 0

  add(milliseconds: number) {
    this.sum += milliseconds
    this.count++
    this.max = Math.max(this.max, milliseconds)
  }

  /** Average and maximum since the last call, or null if there was no measurement. */
  flush(): { average: number; max: number } | null {
    if (this.count === 0) {
      return null
    }
    const result = { average: this.sum / this.count, max: this.max }
    this.sum = 0
    this.count = 0
    this.max = 0
    return result
  }
}
