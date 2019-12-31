# beat-carabiner

A minimal tempo bridge between Pioneer Pro DJ Link and Ableton Link.

> :construction: This project is undergoing major changes. It has
> become a library that manages the binding between Ableton Link and
> Beat Link, incorporating two years of improvements to Beat Link and
> Beat Link Trigger, so that it supports sync in both directions, from
> DJ Link to Ableton Link (as it always has), and also the reverse. To
> eliminate duplication of work, and make sure everyone is always
> getting the latest code, Beat Link Trigger is now using this new
> library instead of having its own copy of the code.
>
> The ability to run beat-carabiner as a standalone project for use in
> headless environments like the Raspberry Pi is moving to a new
> project, [Open Beat
> Control](https://github.com/Deep-Symmetry/open-beat-control). In
> addition to all the features that used to be available in the
> original Beat Carabiner project, it will make Beat Link features
> available to other programs (such as Max/MSP, Max4Live, and Pure
> Data) using Open Sound Control. A few features will be chosen
> initially to prove the concept, and more will be added over time.
>
> :exclamation: For now you can still download a standalone version of
> beat-carabiner from the Releases page here, but there is already a
> [preview
> release](https://deepsymmetry.org/media/open-beat-control.jar) of
> Open Beat Control that does more, so we recommend you switch to that
> (and read its [user guide](https://obc-guide.deepsymmetry.org/)),
> because it will give you many new features for Ableton Link
> synchronization alone, as well as its other capabilities. We would
> also appreciate testing and feeedback to help it reach release
> status.

As
[suggested](https://github.com/brunchboy/beat-link-trigger/issues/26)
by [marek-memsql](https://github.com/marek-memsql), this is a minimal
headless implementation of just enough features from
[beat-link-trigger](https://github.com/brunchboy/beat-link-trigger#beat-link-trigger)
to enable tempo and beat synchronization between a Pioneer Pro DJ Link
session and an Ableton Link session. It is designed for headless,
unattended operation so that it can be run on hardware like the
Raspberry Pi.

As long as beat-carabiner is running, has an active connection to
[Carabiner](https://github.com/brunchboy/carabiner#carabiner), and
sees an active Pro DJ Link Network (using its embedded copy of
[beat-link](https://github.com/brunchboy/beat-link#beat-link)), it will
slave the Ableton Link tempo and beat grid to match the Pioneer gear.

## Installation

Install [Carabiner](https://github.com/brunchboy/carabiner#carabiner),
a Java runtime, and the latest `beat-carabiner.jar` from the
[releases](https://github.com/brunchboy/beat-carabiner/releases) page
on your target hardware.

You may be able to get by with Java 6, but a current release will
perform better and have more recent security updates.

You can either start Carabiner and beat-carabiner manually when you
want to use them, or configure them to start when your system boots.

## Usage

To start beat-carabiner manually, run:

    $ java -jar beat-carabiner.jar

It will log to the terminal window in which you are running it. If you
instead want to run it at system startup, you will probably also want
to set a log-file path, so it logs to a rotated log file in your
standard system logs directory, something like:

    $ java -jar beat-carabiner.jar -L /var/log/beat-carabiner.log

Other options allow you to specify whether it should align to beats
instead of whole bars, the port on which it should contact the
Carabiner daemon, and how many milliseconds of latency it takes for
beat packets from the CDJs to arrive and be processed (you can tweak
this until you get good-sounding synchronization if necessary):

## Options

    -b, --beat-align                  Sync Link session to beats only, not bars
    -c, --carabiner-port PORT  17000  Port number of Carabiner daemon
    -l, --latency MS           20     How many milliseconds are we behind the CDJs
    -L, --log-file PATH               Log to a rotated file instead of stdout
    -h, --help                        Display help information and exit


## Examples

Run without synchronizing bars, with a packet latency of 35 milliseconds:

    $ java -jar beat-carabiner.jar -b --latency 35

## License

Copyright © 2017–2019 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
