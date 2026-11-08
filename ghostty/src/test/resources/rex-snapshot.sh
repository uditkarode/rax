#!/bin/sh
printf '\033]0;rax-fixture\007'
i=1
while [ $i -le 300 ]; do
  printf 'line %03d \033[3%dmcolour %d\033[0m \033[1mbold\033[0m\r\n' $i $((i % 8)) $((i % 8))
  i=$((i + 1))
done
printf '\033[38;2;255;128;0mtruecolor\033[0m \342\224\200\342\224\200 \342\226\210\342\226\210 done\r\n'
exec cat
