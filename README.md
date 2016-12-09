# README #

BURNERVOTE

### Synopsis ###

* BurnerVote is designed to be used with [Burner](https://www.burnerapp.com) and [DropBox](https://www.dropbox.com). Requires Developer accounts with both services.
* Version 0.1
* Send pictures/media to Burner phone via SMS/MMS. Pics will be stored in BurnerVote's app folder
* Send text messages with the filenames to the Burner phone to upvote an item
* Get list of vote totals in JSON form

### Setup ###

* Build application using [Maven](http://maven.apache.org)
* Sign up for DropBox account
* Sign up for Burner account and select a number
* Add BurnerVote to your list of DropBox apps, select a folder for BurnerVote
* Generate an OAuth2 application token
* Pass token & folder path to BurnerVote - either as Java properties at runtime, or (preferred) in an application.conf file (burnervote.dropbox.token & .path)
* Similarly, select binding & port *(default is 0.0.0.0:8080)* burnervote.http.host & .port
* On server, launch executable jar burnervote-run.jar **or** run regular jar burnervote.jar with dependencies on classpath *(pass parameters or ensure config files are in classpath)
* In DropBox account, BurnerVote app, set webhook to <host>/webhook, where <host> is the host running the app *(inluding path to app, if any)*
* Activate Developer mode on your Burner number
* Set hook to <host>/event

### To use ###

* Send pic to Burner number, see it stored.
* Send text containing a filename (from files in Dropbox app folder) to Burner number
* In browser, go to <host>/report to view JSON of votes counted

## Notes ##

This is a code demo only!

All text & source code copyright (c) Bancroft Gracey