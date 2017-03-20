# beat-carabiner

A minimal tempo bridge between Pioneer Pro DJ Link and Ableton Link.

As suggested by [marek-memsql](https://github.com/marek-memsql), this
is a minimal headless implementation of just enough features from
[beat-link-trigger](https://github.com/brunchboy/beat-link-trigger#beat-link-trigger)
to enable tempo and beat synchronization between a Pioneer Pro DJ Link
session and an Ableton Link session. It is designed for headless,
unattended operation so that it can be run on hardware like the
Raspberry Pi.

## Installation

Download from http://example.com/FIXME.

## Usage

Install [Carabiner](https://github.com/brunchboy/carabiner#carabiner),
a Java runtime, and this project on your target hardware, start
Carabiner, and then run this as well.

    $ java -jar beat-carabiner.jar [args]

## Options

    -b, --beat-align                  Sync Link session to beats only, not bars
    -c, --carabiner-port PORT  17000  Port number of Carabiner daemon
    -l, --latency MS           20     How many milliseconds are we behind the CDJs
    -L, --log-file PATH               Log to a rotated file instead of stdout
    -h, --help                        Display help information and exit


## Examples

...

## License

Copyright © 2017 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
