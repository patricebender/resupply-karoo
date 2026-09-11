# Changelog

## [1.0.0](https://github.com/patricebender/resupply-karoo/compare/v0.10.0...v1.0.0) (2026-09-11)


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
