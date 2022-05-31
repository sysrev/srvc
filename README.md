# Standalone  
Every sysrev is a composition of processes. /mvp was my first shot, but incomplete.

```
> tree -L 1 my-project
.
├── .sr [/cache/, /config]
├── gen/...  # depends on nothing  , generates stream
├── map/...  # depends on stream(s), generates stream
├── sink/... # depends on stream(s), generates nothing
├── sr.yaml # dag definition
```

**sr.yaml**  
A `sysrev` stream take a pubmed generator, prioritizes it, reviews it and sinks to sqlite. **[fifo](https://man7.org/linux/man-pages/man7/fifo.7.html)** may work for sharing process streams.
```yaml
flows:
  my-sysrev:
    gen: [{cmd: "gen/pubmed.sh", params: ..., fifo: "pm.fifo"}]
    rev:
      - {cmd: "rev/pty.sh", deps: ["gen/pm.fifo"],  fifo: "pty.fifo"}
      - {cmd: "rev/rev.sh", deps: ["gen/pty.fifo"], fifo: "rev.fifo"}
    snk:
      - {cmd: "snk/sql.sh", deps: "rev.fifo", out: [sqlite.db]}
```

**deploy**  
`sr review my-sysrev` runs the named flow by launching each process.
```
> sr review sysrev
# mkfifo pm.fifo
# gen/pubmed.sh > pm.fifo
#
# mkfifo pty.fifo
# rev/pty.sh pm.fifo > pty.fifo
#
# mkfifo rev.fifo
# rev/rev.sh pty.fifo > rev.fifo
# 
# snk/sql.sh rev.fifo
```

**thoughts**  
1. Some review steps may start UI processes.
2. A standard sink assigns a user+label+answer to a generator entity
3. Generators could be reused anywhere (even non-sysrev applications)
4. flows could potentially read from the sink (needed for prioritization)
5. A docker image could be hosted that normalizes some dependencies


