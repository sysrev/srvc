#!/usr/bin/env Rscript
fifo   <- commandArgs(trailingOnly=TRUE)
input  <- fifo(fifo[[1]],open="r")
output <- fifo[[2]]
repeat{
  line <- readLines(input,n=1)
  if(identical(line,character(0))){break}
  write(line,"\n")
}