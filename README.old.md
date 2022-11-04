
# Installation

Install [homebrew](https://brew.sh) (a brew install is coming in [#7](https://github.com/sysrev/srvc/issues/7)), and use:

```sh
brew install borkdude/brew/babashka
```
```sh
git clone https://github.com/sysrev/srvc.git
```
```sh
ln -s $(pwd)/mvp-bb/sr.clj /usr/local/bin/sr # add to path
```

Alternatively, many srvc repos have a docker container with run instructions.
# Run
sr runs git based reviews (sysrevs). To use it, we need a repo:
```sh
git clone https://github.com/sysrev/srvc-hello.git
cd srvc-hello
```
```sh
sr review simple   # run a simple text flow 
```
```sh
sr review annotate # run a recogito review server 
```
View other flows in the srvc config file `sr.yaml`. 
