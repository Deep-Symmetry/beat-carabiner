# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Fixed

- Triggers mode in Beat Link Trigger couldn't work fully correctly (it
  would be inappropriately influenced by the current Pioneer tempo
  master even if the active trigger was tracking some other player).
  To properly support this kind of use case, Beat Carabiner now offers
  a `:manual` sync mode.

## [0.2.0] - 2019-11-24

### Changed

- This is a completely new purpose for the project, as a shared
  library to be used by Beat Link Trigger, Open Beat Control, and
  other Clojure projects that need to bridge between Beat Link and
  Carabiner. It has been released to support the release of Beat Link
  Trigger version 0.6.0, and will be documented and added to the
  Releases page once Open Beat Control is ready to take over the
  standalone capabilities offered by release 0.1.2.

## [0.1.2] - 2019-10-31

### Fixed

- Release 0.1.1 was not actually tested, and there were some changes
  to the Beat Link API which caused it to crash. Even though this is
  an interim release until Open Beat Control is ready (and people are
  ready to move to it), I want people to be able to take advantage of
  the fixes in Beat Link.

## [0.1.1] - 2019-10-25

### Fixed

- Incorporates a much newer version of beat-link which has many
  important fixes. This is expected to be the final release of this
  project as a standalone executable; those features are moving to the
  planned open-beat-control project, which will add many more too.

## 0.1.0 - 2017-03-20

### Added

- Intitial Release.

[Unreleased]: https://github.com/brunchboy/beat-carabiner/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/brunchboy/beat-carabiner/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/Deep-Symmetry/crate-digger/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/Deep-Symmetry/crate-digger/compare/v0.1.0...v0.1.1
