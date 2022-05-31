#!/usr/bin/env Rscript
fifo   <- commandArgs(trailingOnly=TRUE)
purrr::walk(1:100,~ write(sprintf("{i:%s}\n",.),file=fifo))
