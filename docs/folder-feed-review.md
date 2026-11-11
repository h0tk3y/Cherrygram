# Merged folder feed: review record

A review pass over the whole branch (`3050af6d..` the merged folder channels feed and its CI test pipeline), done with Fable. The fixes were made with Opus. This file records every suggestion and what became of it.

- **Reviewed:** the feature code (FolderFeedController, FolderFeedCache, FolderFeedActivity, FolderFeedEntryCell, the feed's changes in ChatActivity, DialogsActivity, DialogsAdapter and other shared classes) and the CI scaffolding (`.github/workflows/folder-feed-test-build.yml`, `ci/`).
- **Fix commits:** `17b6750b` (CI), `60c51d4f` (app), plus any follow-ups from a verification pass of the app fixes listed at the end.
- **Status values:** **fixed**; **deferred**, with the reason; **accepted**, meaning a known limitation left as is on purpose.

## App code

### Bugs

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | medium | Poll posts offered "Retract vote" and "View stats" in the feed; both sent the synthetic message id, so the request failed. | **fixed**: these items are hidden in the feed (voting already opens the post in its channel). |
| 2 | medium | Content protection of channels did not apply in the feed (the feed has no single chat), so copy, save and share were offered for protected channels and FLAG_SECURE was not set. | **fixed**: copy, save and share follow each post's own channel. The window is secure when any feed channel restricts saving content. |
| 3 | medium | Rebuilding the feed screen's view (e.g. on a theme change) created a new feed and leaked the previous one: its session, requests and observers. | **fixed**: the previous feed is destroyed before a new view is built. |
| 4 | low | Page end flags were computed when a page was built but posted later, so a new post arriving in between could leave the feed thinking the newest post was loaded. | **fixed**: flags are taken when the page is posted. |
| 5 | low | After a cancelled swipe between folders, the action bar feed button showed the state of the folder that was not selected. | **fixed**: the button follows only the selected page. |
| 6 | low | The action bar feed button stayed visible while searching or editing tabs, unlike the other action bar items. | **fixed**: same visibility rules as the other items. |
| 7 | low | The cached channel list (one entry, 1 s) could survive a folder edit, so the entry point could show for a folder that no longer qualifies. | **fixed**: cached per folder and dropped on folder or dialog list changes (5 s backstop). |
| 8 | low | A channel set up from a stale dialog entry could, on its first older page, put posts that belong after the opening point above it. | **fixed**: such posts go to the newer side. |
| 9 | low | Reply previews were accepted from any dialog's reply loads (e.g. a channel opened on top of the feed). | **fixed**: only from the feed's channels. |
| 10 | low | Posts shown from the local database are not refreshed during the session (views, reactions, edits). | **accepted**: the newest end is always confirmed from the network. Refreshing every cached post would cost the requests the cache saves. Views still update through the view-counter queue. |
| 11 | nit | The merge releases posts dated exactly at a channel's frontier, so an album cut by a page boundary could interleave with a same-second post of another channel. | **deferred**: making the comparison strict also holds back each channel's own frontier post. That includes the first unread post on opening, which would then need another round trip. Rare cosmetic case. |
| 12 | nit | The comment button showed no loading state in the feed (it compared real and synthetic ids). | **fixed** |
| 13 | nit | A saved feed position outlives its folder; a new folder reusing the id and containing the same channel would open there. | **deferred**: harmless (it opens at a real post of one of its channels). |
| 14 | nit | The last-empty-view height of the dialogs list does not count the pinned feed entry (same as upstream's hint cell). | **deferred**: cosmetic, unverified. |

### Efficiency

| # | Finding | Status |
|---|---|---|
| 15 | Pages were uncapped, and all MessageObjects were built on the UI thread. | **fixed** (partly): pages are capped at 60 posts and the rest stays buffered. Building MessageObjects off the UI thread is **deferred**: ChatActivity expects them on the UI thread, and capped pages keep the cost bounded. |
| 16 | The one-entry channel cache was recomputed constantly when two folders alternated, and every hit copied the list. | **fixed**: per-folder cache, read-only shared lists. |
| 17 | The next-unread count re-resolved the folder and its channels on every read/new-message update. | **fixed**: summed over the session's channels. |
| 18 | Marking visible posts read on every scroll step allocated two arrays and scanned the posts twice per channel. | **fixed**: arrays reused, one scan. |
| 19 | The in-flight request count was recomputed per channel inside loops. | **fixed** |

### Refactoring and dead code

| # | Finding | Status |
|---|---|---|
| 20 | Dead code: `isEndReached`, `isLoading`, `getChannelsCount`, `Session.filterId`, an unreachable branch in `processEdge`, an empty-page branch in the cache, `folderFeedUnreadCount`, the unused count argument of `cgFolderFeedNewPosts`, four unused strings; `reloadFolderFeed` and `folderFeedFilterId` were needlessly public. | **fixed** |
| 21 | The read pointer computation existed three times. | **fixed**: `FolderFeedController.getReadMax`. |
| 22 | "Channel at its dialog top" was set up three times. | **fixed**: `initAtDialogTop` / `setNewerStart`. |
| 23 | Window scanning was repeated four times. | **fixed**: `scanWindow`. |
| 24 | `probe` and `loadAround` duplicated the request, retry and give-up skeleton (`fetchEdge` too). | **fixed**: `sendHistory` and one `Slot` type for all requests. |
| 25 | Two code paths posted the same pair of notifications. | **fixed**: `postPage`. |
| 26 | Two message deep-copy helpers. | **fixed**: `FolderFeedCache.copyOf`. |
| 27 | Linear `findSource` plus a separate id set. | **fixed**: sources indexed by dialog id. |
| 28 | Magic numbers for cache read states. | **fixed**: named constants; page and fetch sizes documented. |
| 29 | `getFeedUnreadState` and `getFeedUnreadCount` duplicated each other. | **fixed**: the count moved to the session (see 17). |
| 30 | Grouping replies per channel sat inline in an upstream handler. | **fixed**: `loadFolderFeedReplies`; the upstream block keeps a three-line change. |
| 31 | Comments no longer matched the code (retry parallelism, misplaced javadoc, `processEdge`, `getNewestFetchedId`, a fully qualified class name). | **fixed**. The "diditemo" mention is the real preference key, not a typo. |
| 32 | The pinned entry cell does not recolor on a live theme switch until it is recreated. | **deferred**: cosmetic, matches other custom cells. |

No damage to non-feed behaviour was found: the shared-class edits only act on feed messages or when the feature is on.

## CI pipeline

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | medium | The emulator job could not fail: every error was swallowed and it always exited 0. | **fixed**: smoke test that fails when the app does not reach its intro screen or crashes. |
| 2 | medium | The test-account path always ran, always failed silently and installed an unpinned package. | **fixed**: removed (test-server sign-in does not work); kept in git history. |
| 3 | low | The rolling release was deleted before being recreated, so a failed or cancelled run left no download. | **fixed**: kept and its APK replaced; one concurrency group for all refs. |
| 4 | low | The APK name changed with every commit, so there was no stable direct link. | **fixed**: fixed asset name `Cherrygram-folder-feed-test.apk`. |
| 5 | low | A failed signature check could not fail the step (no `pipefail`). | **fixed** |
| 6 | low | The preference-writing step reported success without checking. | **fixed**: removed with the login flow. |
| 7 | low | A failed UI dump returned the previous dump; dialog dismissal did six dumps per poll. | **fixed**: stale dump removed first; one dump per poll. |
| 8 | security | Third-party actions on mutable tags ran with a write token, which checkout also persisted. | **fixed**: read-only by default, write only for publishing; emulator action pinned to a commit; no persisted credentials; screenshots pushed from a separate job. |
| 9 | security | The optional API id/hash secrets are compiled into the public APK. | **accepted, documented** in the workflow header. |
| 10 | security | Unpinned telethon installed on every run. | **fixed**: removed (see 2). |
| 11 | security | The test signing key and its password are public. | **accepted**: throwaway key for test builds only, as stated in the release notes. |
| 12 | refactor | Login-driven UI test and account script unreachable. | **fixed** (see 2) |
| 13 | refactor | `setup-gradle` did nothing useful. | **fixed**: removed. |
| 14 | refactor | The generated `buildSrc/settings.gradle.kts` is probably redundant with the Gradle init script. | **deferred**: needs a CI run without it to confirm; it is harmless. |
| 15 | nit | `--refresh-dependencies` with no cache to refresh. | **fixed**: removed. |
| 16 | nit | Unreachable `else` branch in the account step. | **fixed** (step removed) |
| 17 | refactor | ccache up to 6 GB per commit, saved even when the build never ran. | **fixed** (partly): not saved when the build never started. Lowering the size limit is **deferred** until a run reports the real cache size. |
| 18 | refactor | Gradle edits by exact multi-line matching; one edit re-implemented `replace_once`. | **fixed** (partly): uses `replace_once`. The `afat` context is kept because the short pattern also matches other flavors. |
| 19 | refactor | The Maven mirror goes first for every repository list. | **accepted**: only resolution time is affected. |
| 20 | nit | 330-minute job timeout. | **fixed**: 180 minutes (a cold native build needs more than the usual 15). |
| 21 | nit | Step output interpolated into script text. | **fixed**: passed through the environment. |
| 22 | nit | A preferences file was written into the output directory. | **fixed** (removed with the login flow) |
| 23 | nit | Token in the git remote URL for the screenshot push. | **accepted**: masked by Actions; used only in the separate write job. |
| 24 | nit | "Free disk space" may no longer be needed. | **deferred**: unverified; removing it risks running out of disk space. |

## Verification pass of the app fixes

A second Fable pass compared `60c51d4f` with its parent and looked for regressions. It found no compile problem, paging stall, or lost or duplicated posts. Three low-severity regressions were fixed in the follow-up commit:

| # | Severity | Finding | Status |
|---|---|---|---|
| V1 | low-medium | End flags taken only at post time could be premature when an older and a newer page were queued back to back. An older page could then report "newest post reached" before the last newer page arrived. | **fixed**: a flag holds only if it held when the page was built and still holds when it is posted. This keeps the fix for item 4. |
| V2 | low | Clearing the folder cache depended on observer order: the dialogs screen registers its observers before the lazily created controller, so it could read the stale cache first. | **fixed**: the dialogs screen creates the controller before registering its own observers. |
| V3 | low | The next-unread button disappeared while the feed reloaded (its count came from the session that had just been stopped). | **fixed**: it keeps its count until the first page of the new session. |
| V4 | nit | The action bar feed button was computed even in picker instances of the dialogs screen. | **fixed**: early return restored. |
| V5 | note | The stale-dialog-entry fix (item 8) covers only a channel's first older page; the moved posts are appended below what is already shown. | **accepted**: edge case of an edge case. |
| V6 | note | The newer frontier is 0 instead of "infinite" for a channel without a last message date. | **accepted**: no effect (that edge is exhausted, and a live update makes the next page fetch it). |

## Testing

### How Telegram and Cherrygram test

- **Upstream Telegram** has no JVM unit tests (no `src/test` anywhere). Its only tests are instrumentation tests in `TMessagesProj_AppTests/src/androidTest`. They need a device or emulator, because they load the native `tgnet` library:
  - `generated/Test_All.kt` has about 2,200 generated TL round-trip tests. `buildSrc`'s `TestGeneratorPlugin` regenerates it from `tlscheme/` before every build.
  - `NativeSchemeTest` compares Java and native (de)serialization.
  - `TlsHelloHandshakeTest` checks the fake-TLS client hello.
  - `TestDatabaseMigration` checks that a v120 database migrated by `DatabaseMigrationHelper` matches a freshly created schema.
- **Cherrygram** carries that module over unchanged with each upstream merge. It has no tests of its own customizations, and no CI that runs tests: its only workflow mirrors to GitLab. `MemoryStressTest` is a debug helper that fills the heap, not a test. Its git history comes in one squashed commit per release.

### Unit tests of the feed (JVM)

The merge/paging core and the cache's pure logic live in plain-Java classes that are generic in the message type. They use nothing but `java.util`:

- `FolderFeedMerge` holds frontiers, the watermark merge, `capPage`/`takePage`, fetch order, edge pages, opening windows (probe, around, saved position, newest posts), cache verdicts, live posts and synthetic ids.
- `FolderFeedCacheCore` holds the hole trimming of cached windows and the in-memory stretches.
- `FolderFeedController` and `FolderFeedCache` keep the requests, dialogs list, database and notifications, and call into the core for every decision.

Tests are in `TMessagesProj/src/test/java/uz/unnarsx/cherrygram/chats/feed/`:

- **`FolderFeedSimulationTest`** generates random folders: 2–8 channels with different paces, deleted ids, service messages and same-second posts, including across channels. It runs them against `FakeHistory`, a fake `messages.getHistory` with Telegram's `offset_id`/`offset_date`/`add_offset`/`limit` semantics. `FeedDriver` drives the core the way the controller does, with 1, 2 or 8 requests in flight answered in random order. Over 300 seeds per scenario, it checks that:
  - every post is shown exactly once and no service message is shown;
  - dates never increase down the feed, each channel's ids decrease, and synthetic ids are unique and grow upwards;
  - pages never exceed the cap, and a short page appears only at the end of history.
  It covers opening at the newest posts, at a saved post (also a deleted one), and at the first unread post, where the opening post must be the oldest unread post. It also covers live posts that arrive while requests are in flight, and page capping.

  It also covers reopening from the in-memory stretches: `FeedDriver` records pages the way `FolderFeedCache.write` does. Several feeds are opened one after another in one app session, and the last one must still show every post in order. A channel created after the saved position reopens from memory with no opening or older request.
- **`FolderFeedMergeTest`** has focused cases for each building block, including the start-of-history cache verdicts, plus regression tests for the two bugs below.
- **`FolderFeedCacheCoreTest`** covers hole trimming (holes on either side, the pivot inside a hole, boundaries) and the stretches: merging by overlap or `connectsTo`, copies, window selection, LRU and far-end trimming. It also covers the "start reached" flag: merging, trimming, an empty older page marking the stretch it continues, and `reachesStart`.

To run them:

```sh
ci/unit-tests/run.sh          # plain javac + JUnit 4.13.2 (downloaded, SHA-256 checked); a few seconds
gradle :TMessagesProj:testDebugUnitTest --tests 'uz.unnarsx.cherrygram.chats.feed.*'   # same tests via Gradle
```

`run.sh` compiles only the two core classes with the tests, so the build fails if the core ever picks up an Android or Telegram dependency.

In CI (`.github/workflows/folder-feed-test-build.yml`):

- The `unit-tests` job runs `run.sh` in about a minute, and the APK build `needs` it.
- The build job also runs the tests through Gradle and fails if none ran or any failed.
- Both jobs write their test counts to the job summary.

There are 63 tests, which run in about 3 seconds. A mutation check injected 27 typical mistakes into the core and the tests caught all 27, for example:
- off-by-one watermarks or the wrong `capPage` end;
- no deduplication, or ignored live posts;
- dropped or misplaced start-of-history flags.

### Bugs the tests found (fixed in `e839c083`)

| # | Severity | Finding | Status |
|---|---|---|---|
| T1 | medium | The feed opened at the date of a channel's first unread *message*, which may be a service message such as "post pinned". A busy channel could then fill the first page with read posts, and the feed opened without any unread post in it (no divider, no position). | **fixed**: the opening point is the first unread *post*. |
| T2 | low-medium | A channel set up at its top around the opening point (its last post was older) that got more than 20 posts while the feed was open showed the later ones at the bottom of the feed. The split at the opening point applied only to its first older page. This is the realistic trigger of review note V5. | **fixed**: every older page is split. For channels opened around the point this changes nothing, because their older edge holds only earlier posts. |

One more, in `62a0a9dd` ("accept cached windows of channels whose history starts later"), found by its new tests: when a page that reaches the start of history and an existing stretch begin at the same message, the merge kept only the existing stretch's flag, so the knowledge was lost. **Fixed**: either flag counts.

Still accepted from V5: if the dialogs list itself was stale when the feed opened (no live update), posts found later above the stale top are added at the newer end.

### Emulator tests and the test-server login

The emulator job is still a smoke test (`ci/emulator/ui_test.py`): install, launch, reach the intro screen, no crash. It now hides system error dialogs: an "isn't responding" dialog on the slow software-rendered emulator made `uiautomator dump` fail and the test flaky. ANRs are reported in the summary.

A logged-in emulator test is still not possible. Telegram's test servers answer `PHONE_CODE_INVALID` to the documented test login code (the DC id repeated 5 or 6 times for `99966XYYYY` numbers). `ci/emulator/tg_login_probe.py` (workflow `tg-login-probe.yml`) showed this from GitHub runners for every variant tried:

- Telethon 1.45.0 with legacy `p_q_inner_data` and with `p_q_inner_data_dc` set to 10000 + dc (as `tgnet` does on the test backend);
- TDLib 1.8.70, Telegram's own reference client;
- the repository's API id and upstream's public id 4;
- test DCs 1, 2 and 3 (at `tgnet`'s test addresses), with sign-in immediately and after 15 s.

The server reports `auth.sentCodeTypeSms` with length 5 every time. So the cause is on the server side, not in the client or the script: test numbers no longer accept the fixed code, at least from these IP ranges.

The old script also had client bugs, although they are not the cause: current Telethon disables `sign_up` (1.45.0 checked: it raises `ValueError`, so new numbers could never have been registered through it), and the telethon version was unpinned. The app's own path (country code 999 in a debug build switches `tgnet` to the test DCs) would get the same answer. Run the probe again (push a change to the probe script, or use `workflow_dispatch` once the workflow is on the default branch) to check whether the servers accept the code again.
