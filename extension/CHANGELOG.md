# Changelog

## [1.2.0](https://github.com/patricebender/resupply-karoo/compare/v1.1.0...v1.2.0) (2026-09-23)


### Features

* three APK editions (lean / core-europe / usa) via product flavors ([#65](https://github.com/patricebender/resupply-karoo/issues/65)) ([1dd2d84](https://github.com/patricebender/resupply-karoo/commit/1dd2d848bb72b75f3f9e4c3a5d04dc8b6fd52ab6))

## [1.1.0](https://github.com/patricebender/resupply-karoo/compare/v1.0.0...v1.1.0) (2026-09-23)


### Features

* add USA and Canada regions (Complete + statewise) ([#63](https://github.com/patricebender/resupply-karoo/issues/63)) ([8d9690b](https://github.com/patricebender/resupply-karoo/commit/8d9690b4ab30527136f8c12d0abd36cc5b6b0794))
* brand color scheme with light/dark theme toggle ([#58](https://github.com/patricebender/resupply-karoo/issues/58)) ([dbb2d27](https://github.com/patricebender/resupply-karoo/commit/dbb2d2782d2b92bec992be5a888634de0b9b26bc))
* cache roadbook and favorites per route ([#53](https://github.com/patricebender/resupply-karoo/issues/53)) ([8d1d22c](https://github.com/patricebender/resupply-karoo/commit/8d1d22c2a4535b1d5ff3035086fe4dfe74039ba8))
* extend POI coverage to all of Europe + add pharmacy/ATM/campground categories ([#61](https://github.com/patricebender/resupply-karoo/issues/61)) ([1240ffb](https://github.com/patricebender/resupply-karoo/commit/1240ffb04d37bacd3896a85c1a02476c2ef8ffa0))
* favorite POIs for the current roadbook ([#49](https://github.com/patricebender/resupply-karoo/issues/49)) ([dcb8f8b](https://github.com/patricebender/resupply-karoo/commit/dcb8f8bec927317f128fda5e00e3deda64f692ad))
* filter water POIs to safe sources ([#48](https://github.com/patricebender/resupply-karoo/issues/48)) ([41fcc09](https://github.com/patricebender/resupply-karoo/commit/41fcc09bbce018b13b591fc7bce3f930f6bada27))
* live places around you when no route is loaded ([#45](https://github.com/patricebender/resupply-karoo/issues/45)) ([f1d74ed](https://github.com/patricebender/resupply-karoo/commit/f1d74ed630f30c359f57ebf23e20421b7b71a6c1))
* make live (nearby) mode self-updating and self-explaining ([#59](https://github.com/patricebender/resupply-karoo/issues/59)) ([8e860bd](https://github.com/patricebender/resupply-karoo/commit/8e860bd12d5dfb84e9bb677eae499197796cf15b))
* navigate to a POI from its detail view ([#57](https://github.com/patricebender/resupply-karoo/issues/57)) ([a432f5d](https://github.com/patricebender/resupply-karoo/commit/a432f5d8adf947a7692c03522097e49a9063f6b9))
* route range bracket, live bike marker, and list position-follow ([#54](https://github.com/patricebender/resupply-karoo/issues/54)) ([08207dd](https://github.com/patricebender/resupply-karoo/commit/08207dde1ef637604adf7f5bdb470dc4af524be2))
* show ETA and "open on arrival" status for POIs along a route ([#56](https://github.com/patricebender/resupply-karoo/issues/56)) ([d6153ca](https://github.com/patricebender/resupply-karoo/commit/d6153ca7fced0acbc752f0738d5849e1d0150a5d))
* smart search radius that adapts to POI density ([#51](https://github.com/patricebender/resupply-karoo/issues/51)) ([634eeae](https://github.com/patricebender/resupply-karoo/commit/634eeaeb1859d58db9f0b855afb02cc41ee98409))
* switch POI categories live without rebuilding ([#47](https://github.com/patricebender/resupply-karoo/issues/47)) ([b25b489](https://github.com/patricebender/resupply-karoo/commit/b25b48904fe2fc1b04a58a674c02bff87c568b82))
* theme-aware Resupply logo (cream tile on dark, dark tile on light) ([#60](https://github.com/patricebender/resupply-karoo/issues/60)) ([ecbfd42](https://github.com/patricebender/resupply-karoo/commit/ecbfd426338b38a6fe1fd1900fb0dc4889eca7c6))


### Bug Fixes

* keep restored roadbook classified as a route without a GPS fix ([#52](https://github.com/patricebender/resupply-karoo/issues/52)) ([734b572](https://github.com/patricebender/resupply-karoo/commit/734b57233d0c0c72a1f3d444341e1dd7d375f379))
* region build on Node 24 + keep unnamed POIs; better category icons ([#62](https://github.com/patricebender/resupply-karoo/issues/62)) ([5f166d0](https://github.com/patricebender/resupply-karoo/commit/5f166d06dbb53b9aa3c7e59bb1f409bfb8597c69))


### Performance Improvements

* fetch corridor POIs along a segmented bbox, not one whole-route box ([#55](https://github.com/patricebender/resupply-karoo/issues/55)) ([e1454e5](https://github.com/patricebender/resupply-karoo/commit/e1454e557f74f66642552c1f1ab77823aabae350))

## [1.0.0](https://github.com/patricebender/resupply-karoo/compare/v0.10.0...v1.0.0) (2026-09-15)


### ⚠ BREAKING CHANGES

* rename Roadbook → Resupply and add Karoo library packaging ([#35](https://github.com/patricebender/resupply-karoo/issues/35))

### Features

* add "Open on phone" QR to POI detail ([#34](https://github.com/patricebender/resupply-karoo/issues/34)) ([7aa96fd](https://github.com/patricebender/resupply-karoo/commit/7aa96fd52cef81f892b6417acff60dd5707d0aa2))
* add single-category upcoming-POI fields ([#33](https://github.com/patricebender/resupply-karoo/issues/33)) ([76f6e41](https://github.com/patricebender/resupply-karoo/commit/76f6e41c6a652553e52d23352c2b3c22536b1f1d))
* better UX if no route is loaded yet ([#43](https://github.com/patricebender/resupply-karoo/issues/43)) ([a272f3a](https://github.com/patricebender/resupply-karoo/commit/a272f3a5e79500902ed499a330a4248ae4c1e52e))
* comply with Google Places attribution policy ([#38](https://github.com/patricebender/resupply-karoo/issues/38)) ([dce2fc2](https://github.com/patricebender/resupply-karoo/commit/dce2fc24068234e2deb5a17b32be413ef6ad9fc4))
* glide the header wordmark icon in once a build lands places ([#40](https://github.com/patricebender/resupply-karoo/issues/40)) ([b5fea0f](https://github.com/patricebender/resupply-karoo/commit/b5fea0f9715d6beb0cee5ffd5c6fb3f6bb403201))
* new Resupply icon suite (squircle icon, wordmark header, animated placeholder) ([#36](https://github.com/patricebender/resupply-karoo/issues/36)) ([fd690cf](https://github.com/patricebender/resupply-karoo/commit/fd690cf1355f33e0797ff3177d8df648931165be))
* offer tighter detour radii and default to 250 m ([#42](https://github.com/patricebender/resupply-karoo/issues/42)) ([2cc1332](https://github.com/patricebender/resupply-karoo/commit/2cc1332cc7161be22abc01d6328e43d4ba20be66))
* rank corridor POIs by detour cost and spread them per category ([#44](https://github.com/patricebender/resupply-karoo/issues/44)) ([447218b](https://github.com/patricebender/resupply-karoo/commit/447218b60ba13bcf212b82db6cdd156fd68eb610))
* re-jump to current position on each data-field tap ([#30](https://github.com/patricebender/resupply-karoo/issues/30)) ([44dbad0](https://github.com/patricebender/resupply-karoo/commit/44dbad05bca92e0269705df51be1e690872ce202))
* redesign upcoming-POIs field as a height-adaptive card grid ([#32](https://github.com/patricebender/resupply-karoo/issues/32)) ([9076cb7](https://github.com/patricebender/resupply-karoo/commit/9076cb7d95105644f7eb371a5308dc6ff3a77d75))
* rename Roadbook → Resupply and add Karoo library packaging ([#35](https://github.com/patricebender/resupply-karoo/issues/35)) ([cf5560b](https://github.com/patricebender/resupply-karoo/commit/cf5560bb0f8cd36da3d03f7b3eb508541dabc02f))
* rework settings & options into one screen ([#29](https://github.com/patricebender/resupply-karoo/issues/29)) ([17aa799](https://github.com/patricebender/resupply-karoo/commit/17aa799a38eb61b44741a1bfd6e4239506a64f91))
* splay RouteStrip POI dots off the route by detour side and distance ([#41](https://github.com/patricebender/resupply-karoo/issues/41)) ([011be5e](https://github.com/patricebender/resupply-karoo/commit/011be5ed68a90e3caa1ec29142cbce8ea238428d))


### Bug Fixes

* keep the data field tappable in its message states ([#31](https://github.com/patricebender/resupply-karoo/issues/31)) ([1519853](https://github.com/patricebender/resupply-karoo/commit/1519853b284c889ee3e2eb98ea12bc70b30b35d9))
* match Google Places to the right nearby branch and parse 24/7 hours ([#39](https://github.com/patricebender/resupply-karoo/issues/39)) ([a7cdec5](https://github.com/patricebender/resupply-karoo/commit/a7cdec58f05d0f55304149af21172cad3abf8297))


### Performance Improvements

* cheaper POI DB reads, installs, and corridor queries ([#27](https://github.com/patricebender/resupply-karoo/issues/27)) ([0ef7e4a](https://github.com/patricebender/resupply-karoo/commit/0ef7e4a89a35aee1cb9932ab448fe9da3cc664f4))

## [0.10.0](https://github.com/patricebender/roadbook-karoo/compare/v0.9.0...v0.10.0) (2026-09-07)


### Features

* live position on the route overview timeline ([#25](https://github.com/patricebender/roadbook-karoo/issues/25)) ([9d15d80](https://github.com/patricebender/roadbook-karoo/commit/9d15d80a7c592c83e2b0ea9849ad151e89a5b98f))

## [0.9.0](https://github.com/patricebender/roadbook-karoo/compare/v0.8.0...v0.9.0) (2026-08-31)


### Features

* multi-source water search + Hotels category ([#22](https://github.com/patricebender/roadbook-karoo/issues/22)) ([ad98b20](https://github.com/patricebender/roadbook-karoo/commit/ad98b2003a5938a7cfdfefbe599da504e560b746))

## [0.8.0](https://github.com/patricebender/roadbook-karoo/compare/v0.7.1...v0.8.0) (2026-08-31)


### Features

* add Upcoming POIs data field ([#20](https://github.com/patricebender/roadbook-karoo/issues/20)) ([608265f](https://github.com/patricebender/roadbook-karoo/commit/608265fc20de26f63131d99c11a00fe6f55a84c8))

## [0.7.1](https://github.com/patricebender/roadbook-karoo/compare/v0.7.0...v0.7.1) (2026-08-31)


### Bug Fixes

* bundled Germany seed produced no POIs ([#17](https://github.com/patricebender/roadbook-karoo/issues/17)) ([68f668c](https://github.com/patricebender/roadbook-karoo/commit/68f668c6db4aad853480050060ff03227e8df4a3))

## [0.7.0](https://github.com/patricebender/roadbook-karoo/compare/v0.6.0...v0.7.0) (2026-08-30)


### Features

* additive region installs with bundled Germany seed ([#16](https://github.com/patricebender/roadbook-karoo/issues/16)) ([27c7428](https://github.com/patricebender/roadbook-karoo/commit/27c7428c8c4a24fc982f8a43d1151c3ee632bde7))
* make region release cumulative instead of last-run-wins ([#15](https://github.com/patricebender/roadbook-karoo/issues/15)) ([001bd7b](https://github.com/patricebender/roadbook-karoo/commit/001bd7b9786e2a699baea2c73828a32cdc5c87c4))
* on-demand in-app region downloads for the POI database ([#13](https://github.com/patricebender/roadbook-karoo/issues/13)) ([62f7714](https://github.com/patricebender/roadbook-karoo/commit/62f77143a6fb23ec86f6b43a525ee1c26a3c8712))

## [0.6.0](https://github.com/patricebender/roadbook-karoo/compare/v0.5.0...v0.6.0) (2026-08-30)


### Features

* add ice cream POI category and Hessen coverage ([#11](https://github.com/patricebender/roadbook-karoo/issues/11)) ([085c7df](https://github.com/patricebender/roadbook-karoo/commit/085c7dfd72fe31f30902fe1229258b01ea7044f2))

## [0.5.0](https://github.com/patricebender/roadbook-karoo/compare/v0.4.0...v0.5.0) (2026-08-29)


### Features

* initial roadbook extension + backend scaffold ([64a5b36](https://github.com/patricebender/roadbook-karoo/commit/64a5b3672e5726203f1beed935551cb670326e02))
* redesign whole app ([dbc9d18](https://github.com/patricebender/roadbook-karoo/commit/dbc9d182274c7e0103cee7e1d61c85e9863de470))
* rework Waybook UX, add QR/ice-cream, on-device build controls ([8726bd6](https://github.com/patricebender/roadbook-karoo/commit/8726bd6c49ae0495533a26af58e42fd2554df22c))
* Waybook route overview with on-device POI details ([#3](https://github.com/patricebender/roadbook-karoo/issues/3)) ([71e8e9d](https://github.com/patricebender/roadbook-karoo/commit/71e8e9deab4a5ce3da3ec0210219b29fb3f6fd01))


### Bug Fixes

* **ci:** put release-please annotation on the versionName line ([3fc4a92](https://github.com/patricebender/roadbook-karoo/commit/3fc4a92629b4b10ec4e344fe56aad1c47e839433))

## [0.4.0](https://github.com/patricebender/roadbook-karoo/compare/extension-v0.3.0...extension-v0.4.0) (2026-08-29)


### Features

* redesign whole app ([dbc9d18](https://github.com/patricebender/roadbook-karoo/commit/dbc9d182274c7e0103cee7e1d61c85e9863de470))
* rework Waybook UX, add QR/ice-cream, on-device build controls ([8726bd6](https://github.com/patricebender/roadbook-karoo/commit/8726bd6c49ae0495533a26af58e42fd2554df22c))

## [0.3.0](https://github.com/patricebender/roadbook-karoo/compare/extension-v0.2.0...extension-v0.3.0) (2026-08-28)


### Features

* Waybook route overview with on-device POI details ([#3](https://github.com/patricebender/roadbook-karoo/issues/3)) ([71e8e9d](https://github.com/patricebender/roadbook-karoo/commit/71e8e9deab4a5ce3da3ec0210219b29fb3f6fd01))

## [0.2.0](https://github.com/patricebender/roadbook-karoo/compare/extension-v0.1.0...extension-v0.2.0) (2026-08-26)


### Features

* initial roadbook extension + backend scaffold ([64a5b36](https://github.com/patricebender/roadbook-karoo/commit/64a5b3672e5726203f1beed935551cb670326e02))


### Bug Fixes

* **ci:** put release-please annotation on the versionName line ([3fc4a92](https://github.com/patricebender/roadbook-karoo/commit/3fc4a92629b4b10ec4e344fe56aad1c47e839433))
