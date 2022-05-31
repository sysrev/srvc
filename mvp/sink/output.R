#!/usr/bin/env Rscript

repeat{
  line <- readLines("stdin",n=1)
  if(identical(line,character(0))){break}
  cat(line,"\n")
}