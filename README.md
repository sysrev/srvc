# Installation
HomeBrew install is coming in [#7](https://github.com/sysrev/srvc/issues/7).  

For now, install [homebrew](https://brew.sh), and use:

```sh
brew install borkdude/brew/babashka
```
```sh
git clone https://github.com/sysrev/srvc.git srvc && cd $_/mvp-bb
```
```sh
ln $(pwd)/sr.clj /usr/local/bin/sr # add to path
```

Alternatively, many srvc repos have a docker container with run instructions.
# Run
sr runs git based reviews (sysrev). To use it, we need a repo:
```
git clone https://github.com/sysrev/srvc-hello.git srvc-hello && cd $_
```
run a review flow 
```
sr review simple
```
Run the annotation flow
```
sr review annotate
```
View other flows in the srvc config file `sr.yaml`. 
