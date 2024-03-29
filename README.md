# beat-carabiner

<img alt="a blue carabiner" align="right" width="150" src="doc/assets/carabiner.png">

[![project chat](https://img.shields.io/badge/chat-on%20zulip-brightgreen)](https://deep-symmetry.zulipchat.com/#narrow/stream/278354-carabiner)

A Clojure library for bridging Pioneer Pro DJ Link networks and
[Ableton Link](https://www.ableton.com/en/link/).

[![License](https://img.shields.io/badge/License-Eclipse%20Public%20License%202.0-blue.svg)](#licenses)

> [!NOTE]
> :construction: This project is undergoing major changes. It has
> become a library that manages the binding between Ableton Link and
> Beat Link, incorporating two years of improvements to Beat Link and
> Beat Link Trigger, so that it supports sync in both directions, from
> DJ Link to Ableton Link (as it always has), and also the reverse. To
> eliminate duplication of work, and make sure everyone is always
> getting the latest code, [Beat Link
> Trigger](https://github.com/Deep-Symmetry/beat-link-trigger) is now
> using this new library instead of having its own copy of the code.
>
> The ability to run beat-carabiner as a standalone project for use in
> headless environments like the Raspberry Pi has moved to a new
> project, [Open Beat
> Control](https://github.com/Deep-Symmetry/open-beat-control). In
> addition to all the features that used to be available in the
> original Beat Carabiner project, it makes Beat Link features
> available to other programs (such as Max/MSP, Max4Live, and Pure
> Data) using Open Sound Control. A few features have been chosen
> initially to prove the concept, and more will be added over time.

> [!CAUTION]
> Although you can still download an old standalone
> version of beat-carabiner from the Releases page here, it is no
> longer documented or supported, and we encourage you to switch to
> Open Beat Control (and read its [user
> guide](https://obc-guide.deepsymmetry.org/)), because it gives you
> many new features for Ableton Link synchronization alone, as well as
> its other capabilities.

## Usage

1. Set up a Clojure project using [Leiningen](http://leiningen.org) or
   [Boot](https://github.com/boot-clj/boot#boot--).

1. Add this project as a dependency:
   [![Clojars Project](https://img.shields.io/clojars/v/beat-carabiner.svg)](https://clojars.org/beat-carabiner)

1. In the namespace from which you want to use `beat-carabiner`,
   add this to the `:require` section of the `ns` form:

       [beat-carabiner.core :as beat-carabiner]

Then consult the [API
documentation](https://cljdoc.org/d/beat-carabiner/beat-carabiner/CURRENT/api/beat-carabiner.core)
for how to use it. [![cljdoc
badge](https://cljdoc.org/badge/beat-carabiner/beat-carabiner)](https://cljdoc.org/d/beat-carabiner/beat-carabiner/CURRENT/api/beat-carabiner.core)

Although it is possible to download (or build) and start your own copy
of [Carabiner](https://github.com/Deep-Symmetry/carabiner) if you are
working on an operating system or processor architecture that is not
yet supported by
[lib-carabiner](https://github.com/Deep-Symmetry/lib-carabiner), in most
situations you can let beat-carabiner automatically manage an embedded
instance for you using lib-carabiner. You will need at least Java 9 to
load lib-carabiner, but a current release will perform better and have
more recent security updates.

## Java Version

If you are a Java developer who’d like to use the capability offered
by this library without having to embed Clojure and work with interop
code, or would just find it easier to study a Java implementation,
this has been ported as
[beat-carabiner-java](https://github.com/Deep-Symmetry/beat-carabiner-java).

## Getting Help

<a href="http://zulip.com"><img align="right" alt="Zulip logo"
 src="doc/assets/zulip-icon-circle.svg" width="128" height="128"></a>

Deep Symmetry&rsquo;s projects are generously sponsored with hosting
by <a href="https://zulip.com">Zulip</a>, an open-source modern team
chat app designed to keep both live and asynchronous conversations
organized. Thanks to them, you can <a
href="https://deep-symmetry.zulipchat.com/#narrow/stream/278354-carabiner">chat
with our community</a>, ask questions, get inspiration, and share your
own ideas. You can also open an
[issue](https://github.com/Deep-Symmetry/beat-carabiner/issues) on the
project.

### Logging Configuration

beat-carabiner uses the excellent
[Timbre](https://github.com/ptaoussanis/timbre) logging framework. If
you do nothing, log messages above the `debug` level will be written
to the standard output. But you can configure it however you would
like, as described in its
[documentation](https://github.com/ptaoussanis/timbre#configuration).

## Licenses

<a href="http://deepsymmetry.org"><img align="right" alt="Deep Symmetry"
 src="doc/assets/DS-logo-github.png" width="250" height="150"></a>

Copyright © 2017–2023 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the [Eclipse Public License
2.0](https://opensource.org/licenses/EPL-2.0). By using this software
in any fashion, you are agreeing to be bound by the terms of this
license. You must not remove this notice, or any other, from this
software. A copy of the license can be found in
[LICENSE.md](https://github.com/Deep-Symmetry/beat-carabiner/blob/master/LICENSE.md)
within this project.

The included copies of Carabiner are distributed under the [GNU
General Public License, version
2](https://opensource.org/licenses/GPL-2.0). A copy of the license can be found in
[gpl-2.0.md](https://github.com/Deep-Symmetry/beat-carabiner/blob/master/gpl-2.0.md)
within this project.
