Review:

```
$ ./srvc.clj 
{:i 1}
Include? [Y/n]  y
{:i 2}
Include? [Y/n]  y
{:i 3}
Include? [Y/n]  n
{:i 4}
Include? [Y/n]  n
{:i 5}
Include? [Y/n]  n
{:i 6}
Include? [Y/n]  y
{:i 7}
Include? [Y/n]  y
{:i 8}
Include? [Y/n]  n
{:i 9}
Include? [Y/n]  n
{:i 10}
Include? [Y/n]  y
```

Results:
```
$ sqlite3 sink.db "select * from data"
{"i":1}
{"i":2}
{"i":6}
{"i":7}
{"i":10}
```
