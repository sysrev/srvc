#!/usr/bin/env Rscript

proc <- list()
sbg <- \(x,err){ callr::r_bg(system, args=list(x), stderr=err) }
withr::defer({
  purrr::walk(c("gen.fifo","rev.fifo","gen.log"),file.remove)
  purrr::walk(proc,~.$kill())
})

system('mkfifo gen.fifo')
proc <- c(proc, sbg('gen/gen.R gen.fifo',err="gen.log"))

system('mkfifo rev.fifo')
proc <- c(proc,sbg('map/rev.R gen.fifo rev.fifo > log.txt',err="map.log"))

sys_bg("output/output.R output.fifo", stdout="log.txt")

fs::file_delete("log.txt")
fs::file_create("log.txt")
readLines("log.txt")

fs::file_delete("log2.txt")
fs::file_create("log2.txt")
readLines("log2.txt")
