#!/usr/bin/env esxx-js

esxx.include("esxx/Test.js");

const testRunner = new TestRunner();
const myDir = new URI(esxx.location, ".");

function main(prog, file1) {
  if (arguments.length > 1) {
    // Load specified testcase modules
    for each (let file in Array.slice(arguments, 1)) {
      esxx.include(new URI(esxx.wd, file));
    }
  }
  else {
    // Load all testcase modules
    for each (let file in myDir.load()
	      .file.(/^testmod-.*\.js$/.test(name)).@uri) {
      esxx.include(file);
    }
  }

  return testRunner.run();
}
