# Test audit and proposed fixes

Audit date: 2026-11-07. Source revision: `bc00a29`.

Reviewed **481 tests across 65 files**: 193 JVM tests in 22 files, 278 Android tests in 41 files, and 10 script tests in two files. Also inspected the Android test helpers, build configuration and shard runner. This is a source audit; tests were not run and runtime savings were not measured. Test and production files are unchanged.

The clearest waste is two constructor/getter tests, repeated Android setup for overlapping scenarios, and static assertions inside otherwise useful tests. Most small tests cover real rules. Persistence round trips, dynamic labels, hit testing, keyboard behavior and graph geometry should stay.

## First fixes

### 1 Remove two constructor and getter tests

In [WorkoutSessionTest](../app/src/test/java/lofit/WorkoutSessionTest.kt#L488):

- `routineExercise_preservesPrescribedWork` at line 488 creates a `RoutineExercise` and reads back the supplied set count, duration and rest.
- `routineExercise_allowsDifferentWeightAndRepsForEachSet` at line 512 reads back the weights, repetitions and types supplied to its constructor.

Neither exercises a transformation or business rule. Remove both. Keep `historyWorkout_convertsCompletedWorkToRoutine` in the same file and `routineWithoutOwnHistoryUsesExactSavedPrescriptionDespiteOtherHistory` in [RoutineWorkoutsTest](../app/src/test/java/lofit/RoutineWorkoutsTest.kt#L191); these check prescriptions through real conversion, including heterogeneous sets and fresh IDs.

### 2 Trim static catalog snapshots

[WorkoutSessionTest.defaultLibrary_hasFullBalancedCatalog](../app/src/test/java/lofit/WorkoutSessionTest.kt#L399) combines useful data validation with low-value snapshots:

- Literal enum labels at line 402.
- Exact catalog and weighted-copy counts at lines 403 and 440.
- The same ID-uniqueness assertion twice, at lines 430 and 475.

Remove the duplicate assertion. Drop the wording and exact totals unless they are intentional versioned catalog requirements. Keep required IDs, valid metadata, unique IDs, weighted-copy relationships and special image mappings. Move these checks into a catalog-focused test group when reorganizing tests.

Also replace the literal `TrendSpan.months` list in [ExerciseTrendTest](../app/src/test/java/lofit/ExerciseTrendTest.kt#L60) with observable span-boundary cases if it needs coverage. Keep the date, baseline and all-history behavior.

### 3 Fold one repeated discard flow into existing coverage

[WorkoutFlowTest.discardWorkout_singleExerciseWithNoDoneSetsNeedsNoConfirmation](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L1591) repeats the final discard action in `home_leavingWorkoutWithExerciseShowsTileThenBackLeavesApp` at line 614. Both seed one bench-press exercise with no completed sets.

Add the short test's repository assertion, `activeWorkout == null`, after the existing home-flow discard, then remove the separate test. Keep the home flow's background/reopen/resume behavior and the distinct confirmation cases for other workout states.

### 4 Strengthen the weighted-bodyweight display test

[WorkoutFlowTest.weightedBodyweightCopy_showsWeightColumn](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L1353) launches an activity only to assert the seeded title, exercise name, weight and repetitions. It does protect conditional weight-field visibility, so deleting it outright would lose a UI contract.

Fold the weighted case into the neighboring bodyweight logging coverage, or extend it to edit weight, complete a set and verify the persisted load. Preserve both branches: ordinary bodyweight hides the weight field and logs zero; its weighted variant accepts and logs added weight. Keep independent fixtures if combining them makes the UI flow cumbersome.

### 5 Fix tests that assert less than their names promise

- [WorkoutScaleReaderTest.signalLossInterruptsWithoutInventingAReleaseAndNextReadingRestoresStatus](../app/src/androidTest/java/lofit/WorkoutScaleReaderTest.kt#L77) installs empty reading and interruption callbacks. It proves stale status and recovery, but cannot detect a missing interruption or an invented zero-load reading. Capture both callbacks; after staleness assert an additional interruption and an unchanged reading list, then verify recovery adds only the real reading. Account for the interruption emitted during initial setup.
- [BodyMeasurementGraphViewTest.plotsSingleReadingsDecreasesAndUnevenDecimalsWithoutLosingThePlotArea](../app/src/androidTest/java/lofit/BodyMeasurementGraphViewTest.kt#L16) only requires any coral pixel, echoes `view.measurements`, and checks one accessibility value. A misplaced dot can pass. Check bounded plotted coordinates, both readings, and the downward value trend using tolerant geometry assertions. Remove the getter echo; keep the rendering and accessibility checks.
- Replace Kotlin `assert(...)` with JUnit assertions in [WorkoutFlowTest](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L1030), at lines 1030, 1055 and 3519, and [WorkoutSessionTest](../app/src/test/java/lofit/WorkoutSessionTest.kt#L52), at lines 52–56, 69, 404, 428–429, 468 and 472–474. The checks otherwise depend on assertion configuration. This audit does not establish that they are currently disabled.

## Other consolidation candidates

These are proposals to preserve assertions while sharing fixtures or removing overlapping setup. Fewer methods alone is not a useful goal. Keep named cases and avoid turning independent regressions into one long scenario whose first failure hides the rest.

### JVM history and record coverage

| Targets | Suggested fix and coverage to retain |
| --- | --- |
| [RoutineWorkoutsTest](../app/src/test/java/lofit/RoutineWorkoutsTest.kt#L259): `emptyWorkoutCopiesLoggedSetsWithoutRestOverride`, `emptyWorkoutDoesNotCopyPreviousInheritedRest`, `emptyWorkoutDoesNotCopyRestFromOpenWorkoutHistory`, `routineExerciseCopiesPreviousEffectiveRest` | Use named cases for open/routine history, destination context and null/zero/positive rest overrides. Keep copy independence, clean runtime fields and all set values. These are distinct branches with repeated fixtures. |
| [WorkoutSessionTest.lastKnownSets_usesNewestLoggedSetsAndCopiesAllValues](../app/src/test/java/lofit/WorkoutSessionTest.kt#L533) and the preceding history-copy group | One richer fixture can cover both APIs. Retain out-of-order history selection, the public `lastKnownSets` wrapper and the separate newer-history-with-no-logged-sets case. |
| [WorkoutSessionTest](../app/src/test/java/lofit/WorkoutSessionTest.kt#L923): `oneRepMaxes_useStoredHighestWeightLongestHoldAndSetVolume`, `oneRepMaxes_useRepetitionsForBodyweightExercise` | Move final query assertions into `exerciseRecords_appendProgressionAndKeepSnapshotsIndependentOfHistory` and `exerciseRecords_trackHoldsAndBodyweightRepsWithoutVolume`, then retire the repeated fixtures. Preserve timed results, bodyweight reps, null volume and exclusion of active/unrecorded work. |
| [WorkoutSessionTest.workoutExercise_usesWorkoutRestWhenOverrideIsEmpty](../app/src/test/java/lofit/WorkoutSessionTest.kt#L502) and [PauseEverySecondSetTest.enabledRestsAfterEachPairAndDisabledRestsAfterEachSet](../app/src/test/java/lofit/PauseEverySecondSetTest.kt#L11) | Optional tiny cleanup: consolidate default/override/zero rest cases under the latter, which calls the same fallback through `restSecondsAfterSet`. Keep pair sequencing. |
| [HevyExerciseMappingTest](../app/src/test/java/lofit/HevyExerciseMappingTest.kt#L135): `repsOnlyTemplateCannotLoseAddedWeight`, `zeroLoadExercisesCanUseHevyUnweightedDefaults` | Share a compatibility matrix for zero/positive load and repetition/duration targets. Keep alias matching and metric-specific behavior. Placeholder titles are external template-reuse contracts, so retain those assertions. |

JVM consolidation mainly reduces maintenance. Preserve separate record tie policies: newest tied stored record and earliest tied completed set in one workout are different rules.

### Storage and sharing

- [RepositoryPersistenceTest.startup_badJsonAndNewerSchemaNeverBecomeEmptyData](../app/src/androidTest/java/lofit/RepositoryPersistenceTest.kt#L353) overlaps [JsonStorageMigrationTest.invalidOrFutureJson_doesNotFallBackToStaleXmlOrOverwriteEitherFile](../app/src/androidTest/java/lofit/JsonStorageMigrationTest.kt#L281). Consolidate the malformed/future-schema matrix under one owner, with **both stale XML present and absent**. Retain unchanged JSON/XML and no-unwanted-backup assertions. The two current setups are not interchangeable.
- [SharedWorkoutPlanTest](../app/src/androidTest/java/lofit/SharedWorkoutPlanTest.kt#L99) and [WorkoutPlanFileTest](../app/src/androidTest/java/lofit/WorkoutPlanFileTest.kt#L55) repeat full-plan gzip round trips and malformed/oversized payload cases. Share a named contract fixture while invoking **both adapters**: they implement separate decompression paths. Preserve damaged-gzip and expanded-size checks through each, their different stream/byte-array input limits, and actual content-provider, permission and manifest behavior.
- **13 Android methods are JVM migration candidates:** all 10 `SharedWorkoutPlanTest` methods; `WorkoutPlanFileTest.fileIsGzipJsonAndPreservesFullPlan` and `rejectsDamagedFilesAndLimitsCompressedAndExpandedSize`; and [LiveMeasurementPersistenceTest.sharedPlanPreservesModifierAndManualChoiceWithoutReusingAnOrdinaryExercise](../app/src/androidTest/java/lofit/LiveMeasurementPersistenceTest.kt#L120). They exercise data, JSON and gzip without Android UI or storage. Add a real test-only `org.json` implementation and verify compatibility with Android JSON behavior and JVM class loading. Keep the Android provider round trip. Do not simply relocate files while relying on Android stub methods.
- [RepositoryPersistenceTest](../app/src/androidTest/java/lofit/RepositoryPersistenceTest.kt#L55): combine the shared round-trip setup in `pairedRestPersistsAndDefaultsOffForOldPlans` and `exerciseOptionsPersistInPlansButAreOmittedFromHistory`. Retain old-data defaults, source-routine fields and intentional omission from history. Settings default/consent cases can share helpers, but tri-state health consent, default-off voice and MCP reset are separate contracts.
- [HevySettingsTest](../app/src/androidTest/java/lofit/HevySettingsTest.kt#L14): optionally fold `rotatedTokenPairIsSavedTogether` into `unauthorizedRequestRefreshesAndRetriesWithNewPair` by starting with a `Bearer`-prefixed token. Keep normalization, paired-token replacement and reload checks. These are persistence/protocol tests, not getter tests; retaining the focused case is also reasonable.

### Android flows that can absorb narrow checks

In addition to the discard and weighted-bodyweight cases above:

| Narrow test | Proposed destination and conditions |
| --- | --- |
| [WorkoutFlowTest.symmetricExerciseMenu_hidesPairedRest](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L3279) | Add a symmetric row to `asymmetricRestMenu_persistsAndRestsOnlyAfterEverySecondSet`. Keep the negative menu check and the actual asymmetric toggle/persist/rest sequence; scope matchers by exercise. |
| [WorkoutFlowTest.notification_noCompleteActionWithoutPlannedSet](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L1199) | Assert the notification before the first Add set in `emptyWorkout_canStartAddExerciseAndLogWarmupSet`. Preserve an exercise-present, zero-planned-set fixture and the notification wait. A completely empty workout is a different case. |
| [WorkoutFlowTest.historyEntry_cannotContinueYesterdayWorkout](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L3191) | `historyEntry_canContinueFinishedWorkout` already checks an older non-resumable workout. Make that fixture yesterday at 23:59; retain unchanged stored state and View/Options checks before continuing today's workout. Keep JVM midnight/overnight rules. |
| [WorkoutFlowTest.routineAddedSet_preservesFractionalWeight](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L1272) | Use fractional weights in [RoutineWorkoutFlowTest.routineAddSetCopiesLeftAndRightRepsAndLatestWeights](../app/src/androidTest/java/lofit/RoutineWorkoutFlowTest.kt#L160), asserting display and saved values. Preserve symmetric Add set coverage elsewhere and the separate active-workout fractional-weight/storage/volume test. |

Further opportunities need more care:

- **Routine defaults:** [WorkoutFlowTest](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L2098) methods `startingRoutine_keepsEachExercisesSavedSetCountWhenHistoryDiffers` and `startingRoutine_usesLatestValuesWithSavedSetCount` overlap [RoutineWorkoutFlowTest.sameRoutineHistoryWinsAndOpenWorkoutStillUsesNewestHistory](../app/src/androidTest/java/lofit/RoutineWorkoutFlowTest.kt#L457). Use a richer own-history fixture with two exercises and longer/shorter historical set counts. Preserve both start entry points, routine identity, rest overrides, open-workout selection, and the opposite no-own-history policy. JVM tests cover the exhaustive data decisions.
- **Record screens:** [WorkoutFlowTest.records_listsHighestWeightLongestHoldAndSetVolume](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L2355) can contribute its computed values and timed exercise to `recordTrend_opensFromRecordsAndWorkoutHistoryAndChangesSpan` at line 2255. Keep both entry routes and actual span switching; drop static explanatory-copy assertions.
- **Navigation:** [WorkoutFlowTest.navigation_backReturnsToPreviousTab](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L445) could become a short two-tab hop in `navigation_eachTabReturnsHomeThenBackLeavesAndWorksAfterReopening`. Keep draft recovery, child settings pages and external Health Connect return separate.
- **Replacement states:** [ExerciseEditingFlowTest](../app/src/androidTest/java/lofit/ExerciseEditingFlowTest.kt#L367) methods `fullyLoggedExerciseReplacesOnlyWithMatchingFlagsAndSupportsUndoAndRecreation`, `compatibleReplacementKeepsBothLoggedAndPendingSets` and `compatibleReplacementKeepsLoggedSetsWithoutPlans` can share a named fixture/helper. Keep all three states, incompatible-choice filtering, actual replace/undo actions and recreation. Existing JVM tests do not replace these cases. Shared helpers do not eliminate launches.
- **Sticky graphs:** [LiveWorkoutMeasurementFlowTest](../app/src/androidTest/java/lofit/LiveWorkoutMeasurementFlowTest.kt#L207) methods `graphStaysVisibleThroughManySetsAndReturnsToItsOriginalPosition` and `graphLeavesWithItsExerciseAndNextGraphBecomesSticky` could share one bounded scroll journey. Preserve first/second graph ownership and return-to-origin assertions. Lower priority because merging reduces failure isolation.
- **Voice layers:** `speak` helpers in [VoiceTimerFlowTest](../app/src/androidTest/java/lofit/VoiceTimerFlowTest.kt#L82) and [LockedWorkoutVoiceTest](../app/src/androidTest/java/lofit/LockedWorkoutVoiceTest.kt#L118) repeatedly construct the real speech detector. Keep real-audio foreground and locked/service integration, plus utterance deduplication. Other command/UI permutations can use existing command callbacks while the dedicated native-audio suite owns the phrase matrix. Preserve microphone and lifecycle behavior; avoid global recognizer caching that could hide reset bugs.

## Trim cosmetic assertions inside useful flows

Remove only implementation-specific checks unless exact styling is an explicit UX requirement:

| Location | Candidate to trim | Keep |
| --- | --- | --- |
| [WorkoutFlowTest](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L573), `home_placesStartButtonAndDiscardsEmptyWorkoutWithoutConfirmation`, lines 573–585 | Shared-parent and exact adjacent-child-index checks. | Reachable Start, retained picker state, empty-workout discard, notification cleanup and recreation. |
| [WorkoutFlowTest](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L958), `emptyWorkout_canStartAddExerciseAndLogWarmupSet`, lines 958–984 and 1000–1008 | Exact card width and drawable intrinsic size. | Actual add/edit/complete/finish actions; usable touch targets where required. |
| [WorkoutFlowTest](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L2493), record-crown tests at lines 2493–2496 and 2580–2613 | Exact child count, widget class, null background, zero padding and sibling hierarchy. | Crown changes after completion/edit/uncheck, timer access and recreation. |
| [WorkoutFlowTest](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L2681), history-derived routine at lines 2681–2697; asymmetric side controls at 2749–2769 | Separator existence, generic button placement, corner radii and exact sibling coordinates. | All derived exercises and prescriptions; side cycling and persisted values. |
| [ActiveWorkoutTitleTest](../app/src/androidTest/java/lofit/ActiveWorkoutTitleTest.kt#L46) | Four repeated exact title/timer baseline checks. | Rename commit, typing/recreation, blank rejection and source-routine isolation. Keep one alignment contract if required. |
| [ExerciseEditingFlowTest.menuShowsRemoveFirstAndOpeningOrCancellingDoesNotChangeWorkout](../app/src/androidTest/java/lofit/ExerciseEditingFlowTest.kt#L189) | Exact menu-position order. | Opening/cancelling does not mutate; selection restrictions and removal work. |

## Additional assertion and isolation fixes

- [McpProtocolTest.exerciseWriteNotificationsNeverMutateData](../app/src/androidTest/java/lofit/McpProtocolTest.kt#L586) sends an edit notification without `exerciseId` or an editable fixture. Its unchanged-data check cannot prove that a **valid edit** was suppressed. Combine notification coverage from `initializationDiscoveryAndNotificationsFollowMcp` into named valid create-routine/create-exercise/edit-exercise cases. Seed a custom exercise for edit; assert null response, unchanged model/file and zero callbacks. Keep discovery and valid request coverage.
- [WorkoutFlowTest.routineSetDelete_keepsSurvivingRowsMounted](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L1881) checks surviving view identity without saving or proving the removed row disappeared. Add a visible row-count/nonexistence assertion and save/reload result. Keep identity checks because they protect focus/flicker behavior.
- [WorkoutBluetoothTest.broadcastStartsAndStops](../app/src/androidTest/java/lofit/WorkoutBluetoothTest.kt#L73) observes successful advertise start; `stop()` is only cleanup. Rename it to a start smoke test or assert an observable stop/restart effect. The large payload is not proof of peer transfer. Hardware assumptions can skip the test.
- Older [WorkoutFlowTest](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt#L1079) cases leave directly started activities open; the notification helper explicitly works around this. Use `ActivityScenario.use` or `try/finally` cleanup when consolidating. Avoid adding sleeps or production hooks.
- [test_sharded_runner.py.test_missing_and_duplicate_completions_are_errors](../scripts/tests/test_sharded_runner.py#L40) only checks that two errors exist. Assert the missing and duplicated method identities too. Keep all five runner tests: they cover different false-success risks.

## Coverage to keep

- Small parsing, rounding, fallback, null and boundary tests exercise logic; they are not useless getters. Likewise, repository save/reload/default/migration tests protect actual stored data.
- Keep keyboard, first-draw, clipping, scroll obstruction, hit-target, graph-bound and lifecycle checks. Their geometry or text assertions describe observable behavior.
- Keep all four routine-finish checkbox combinations. They already share a helper and test distinct UI argument wiring; current JVM tests do not provide four independent fresh-fixture cases.
- Keep native voice/audio false-positive, reset, silence and noise tests alongside pure text parsing. Keep locked/background and foreground ownership paths distinct.
- Keep image decode/resource/gender-cache tests, Health Connect permissions/query/paging tests, MCP socket boundaries, atomic storage failures and migration backups. Similar assertions at different boundaries are not automatic duplicates.

## Suggested implementation order

1. Strengthen misleading assertions and ensure unconditional assertions and activity cleanup.
2. Remove the two JVM constructor echoes and trim static snapshots/cosmetic internals.
3. Fold the six narrow Android scenarios identified above, preserving their unique assertions.
4. Move the 13 pure codec/import cases to JVM after checking JSON compatibility; consolidate repeated fixtures without losing adapter or state coverage.
5. Consider the broader UI/voice merges only if profiling supports them.

For a later implementation, run relevant JVM/script checks and the same Android suite before and after with **two shards**, using `./scripts/test-sharded.py --shards 2`. Use the established profiling workflow for speed comparisons; the current runner has no `--profile` option. Report observed differences only, including suite/case changes and shard balance. Parameterization still executes every case, and combining too much can worsen parallel balance. Run `./gradlew spotlessApply` last, with no test rerun afterward.


## Complete file inventory

Counts are declared test methods/cases, not the number of rows inside test loops. Every test body in these files was reviewed.

### JVM tests

| File | Tests | Disposition |
| --- | ---: | --- |
| [BodyMeasurementsTest](../app/src/test/java/lofit/BodyMeasurementsTest.kt) | 3 | Keep parsing, validation, ordering, decreases and independent sides. |
| [BodyWeightTest](../app/src/test/java/lofit/BodyWeightTest.kt) | 5 | Keep exact/nearest/mean selection, rounding and invalid inputs. |
| [CalendarStatsTest](../app/src/test/java/lofit/CalendarStatsTest.kt) | 5 | Keep cardio eligibility, distinct days, completed-week averages and muscle credits. |
| [ExerciseEditingTest](../app/src/test/java/lofit/ExerciseEditingTest.kt) | 6 | Keep paired ordering, copy/reset, nonalternating sets and empty defaults. |
| [ExerciseRecencyTest](../app/src/test/java/lofit/ExerciseRecencyTest.kt) | 5 | Keep retirement, recency ties, unfinished exclusion and finish updates. |
| [ExerciseTrendTest](../app/src/test/java/lofit/ExerciseTrendTest.kt) | 11 | Keep filtering, baseline, calendar/DST and metrics; trim enum snapshot. |
| [HevyExerciseMappingTest](../app/src/test/java/lofit/HevyExerciseMappingTest.kt) | 10 | Keep export contracts; share overlapping zero/positive-load compatibility fixtures. |
| [LiveMeasurementSeriesTest](../app/src/test/java/lofit/LiveMeasurementSeriesTest.kt) | 3 | Keep cutoff, segmentation, bounded memory and invalid/out-of-order rejection. |
| [LiveWorkoutMeasurementTest](../app/src/test/java/lofit/LiveWorkoutMeasurementTest.kt) | 20 | Keep state transitions and actual logged/planned effects beyond averaging math. |
| [McpHttpServerTest](../app/src/test/java/lofit/McpHttpServerTest.kt) | 5 | Keep real socket dispatch, trust boundaries, size/error handling and cleanup. |
| [MuscleWorkloadTest](../app/src/test/java/lofit/MuscleWorkloadTest.kt) | 9 | Keep caps, weighting, deduplication, normalization and percentage rules. |
| [PauseEverySecondSetTest](../app/src/test/java/lofit/PauseEverySecondSetTest.kt) | 2 | Keep paired rest and conversion behavior; optional home for fallback assertions. |
| [PullWeightAverageTest](../app/src/test/java/lofit/PullWeightAverageTest.kt) | 13 | Keep distinct signal shapes, noise, timestamps, rounding and interruption cases. |
| [RoutineWorkoutsTest](../app/src/test/java/lofit/RoutineWorkoutsTest.kt) | 16 | Share rest/history fixtures; keep selection, baseline, notes and opt-in branches. |
| [VoiceAudioRouteTest](../app/src/test/java/lofit/VoiceAudioRouteTest.kt) | 8 | Keep selection, delayed arrival, rejection, cleanup and reconnect. |
| [VoiceCommandTest](../app/src/test/java/lofit/VoiceCommandTest.kt) | 3 | Keep valid/invalid parser matrices and utterance deduplication. |
| [VoiceTimerTargetTest](../app/src/test/java/lofit/VoiceTimerTargetTest.kt) | 12 | Keep visibility, eligibility, priority, deleted targets and completion state. |
| [WeightFormattingTest](../app/src/test/java/lofit/WeightFormattingTest.kt) | 4 | Keep locale, precision, round-trip edits and decimal arithmetic. |
| [WhC06Test](../app/src/test/java/lofit/WhC06Test.kt) | 3 | Keep frame decoding, byte order, sign/scale and invalid boundaries. |
| [WorkoutHeartRateTest](../app/src/test/java/lofit/WorkoutHeartRateTest.kt) | 9 | Keep sample windows, interpolation, resumptions and backfill boundaries. |
| [WorkoutSessionTest](../app/src/test/java/lofit/WorkoutSessionTest.kt) | 33 | Remove two getter echoes; trim catalog snapshots; consolidate record/history queries. |
| [WorkoutVoiceSessionTest](../app/src/test/java/lofit/WorkoutVoiceSessionTest.kt) | 8 | Keep command effects, stale targets, timing and disabled/finished guards. |
| **Total** | **193** | **22 files** |

### Android tests

| File | Tests | Disposition |
| --- | ---: | --- |
| [ActiveWorkoutTitleTest](../app/src/androidTest/java/lofit/ActiveWorkoutTitleTest.kt) | 2 | Keep distinct rename/recreation paths; trim repeated exact baseline styling. |
| [BodyMeasurementFlowTest](../app/src/androidTest/java/lofit/BodyMeasurementFlowTest.kt) | 1 | Keep dated paired entry, save/edit/delete, graph binding and recreation. |
| [BodyMeasurementGraphViewTest](../app/src/androidTest/java/lofit/BodyMeasurementGraphViewTest.kt) | 1 | Strengthen plotted geometry/accessibility; remove constructor-input echo. |
| [BodyMeasurementMapViewTest](../app/src/androidTest/java/lofit/BodyMeasurementMapViewTest.kt) | 1 | Keep anatomical grouping and real touch targets across models/sides. |
| [BodyMeasurementPersistenceTest](../app/src/androidTest/java/lofit/BodyMeasurementPersistenceTest.kt) | 2 | Keep part/side round trips, edit/delete/reset and old-data defaults. |
| [CalendarCardioTest](../app/src/androidTest/java/lofit/CalendarCardioTest.kt) | 2 | Keep calendar and same-day picker behavior; exact icon offsets are optional styling. |
| [CraneScaleScreensTest](../app/src/androidTest/java/lofit/CraneScaleScreensTest.kt) | 4 | Keep device selection, graph data, no writes, retry and stale-callback lifecycle. |
| [ExerciseEditingFlowTest](../app/src/androidTest/java/lofit/ExerciseEditingFlowTest.kt) | 13 | Share replacement fixtures; keep drafts, validation, undo, keyboard and recreation. |
| [ExerciseImageAliasesTest](../app/src/androidTest/java/lofit/ExerciseImageAliasesTest.kt) | 2 | Keep resource/alias resolution and repeated gender-cache switching. |
| [ExerciseLibraryFilterTest](../app/src/androidTest/java/lofit/ExerciseLibraryFilterTest.kt) | 3 | Keep populated/empty filters, retained search, ordering and cardio metadata. |
| [ExerciseNotesTest](../app/src/androidTest/java/lofit/ExerciseNotesTest.kt) | 4 | Keep inheritance, editing/clearing and lifecycle; old-schema case could move to storage suite. |
| [ExercisePicturesTest](../app/src/androidTest/java/lofit/ExercisePicturesTest.kt) | 4 | Keep decoded bundled assets, settings, import/replace/cancel/delete and corrupt-input cleanup. |
| [ExerciseRecencyTest](../app/src/androidTest/java/lofit/ExerciseRecencyTest.kt) | 6 | Keep commit-time ordering, selection/search, keyboard/scroll reachability and compatibility. |
| [ExerciseRecordsStorageTest](../app/src/androidTest/java/lofit/ExerciseRecordsStorageTest.kt) | 2 | Keep history independence, reattachment/appends/reset and no old-history backfill. |
| [HealthConnectHeartRateReaderTest](../app/src/androidTest/java/lofit/HealthConnectHeartRateReaderTest.kt) | 7 | Keep platform queries, aggregation/paging, consent, permission failure and UI integration. |
| [HealthConnectWeightReaderTest](../app/src/androidTest/java/lofit/HealthConnectWeightReaderTest.kt) | 1 | Keep real OS reads, interpolation, active snapshot and consent off/on. |
| [HeartRatePersistenceTest](../app/src/androidTest/java/lofit/HeartRatePersistenceTest.kt) | 1 | Keep saved/missing averages and old-history compatibility. |
| [HevySettingsTest](../app/src/androidTest/java/lofit/HevySettingsTest.kt) | 2 | Optional normalization/refresh/retry/reload merge; retain both-token persistence. |
| [HistoryPagingTest](../app/src/androidTest/java/lofit/HistoryPagingTest.kt) | 2 | Keep page boundaries, order, scroll, fixed controls and navigation. |
| [JsonStorageMigrationTest](../app/src/androidTest/java/lofit/JsonStorageMigrationTest.kt) | 17 | Consolidate malformed/future-schema overlap; retain distinct recovery/migration failures. |
| [LazyMuscleMapsTest](../app/src/androidTest/java/lofit/LazyMuscleMapsTest.kt) | 3 | Keep deferred work, recycling, detach cancellation and font-scale layout stability. |
| [LiveMeasurementPersistenceTest](../app/src/androidTest/java/lofit/LiveMeasurementPersistenceTest.kt) | 3 | Keep two persistence tests; move one pure shared-plan case to JVM. |
| [LiveWorkoutMeasurementFlowTest](../app/src/androidTest/java/lofit/LiveWorkoutMeasurementFlowTest.kt) | 12 | Keep scale/voice/manual transitions, graphs and errors; optional sticky-scroll merge. |
| [LockedWorkoutVoiceTest](../app/src/androidTest/java/lofit/LockedWorkoutVoiceTest.kt) | 6 | Keep locked/background/destruction ownership; reduce repeated recognizer work selectively. |
| [McpExercisePicturesTest](../app/src/androidTest/java/lofit/McpExercisePicturesTest.kt) | 5 | Keep decode/resize, persistence, shared ownership, invalid input and rollback. |
| [McpProtocolTest](../app/src/androidTest/java/lofit/McpProtocolTest.kt) | 12 | Fix valid-edit notification fixture; share notification cases; retain protocol/write/rollback behavior. |
| [McpSettingsTest](../app/src/androidTest/java/lofit/McpSettingsTest.kt) | 3 | Keep server toggle/lifecycle, port fallback/restart, UI refresh and real LAN rejection. |
| [MuscleMapViewTest](../app/src/androidTest/java/lofit/MuscleMapViewTest.kt) | 1 | Keep actual shading and caps across models/sides. |
| [OfflineTimerVoiceTest](../app/src/androidTest/java/lofit/OfflineTimerVoiceTest.kt) | 4 | Keep async unload, close-once, microphone shutdown and target changes. |
| [RepositoryPersistenceTest](../app/src/androidTest/java/lofit/RepositoryPersistenceTest.kt) | 14 | Share schema/options/settings fixtures; keep storage and compatibility boundaries. |
| [RetiredExercisePersistenceTest](../app/src/androidTest/java/lofit/RetiredExercisePersistenceTest.kt) | 1 | Keep retained history/routines/records and exclusion from selection/MCP. |
| [RoutineWorkoutFlowTest](../app/src/androidTest/java/lofit/RoutineWorkoutFlowTest.kt) | 18 | Absorb fractional Add set; share history fixtures; retain keyboard and four finish choices. |
| [SharedWorkoutPlanTest](../app/src/androidTest/java/lofit/SharedWorkoutPlanTest.kt) | 10 | Move ten pure cases to JVM; preserve privacy/import/identity/validation/compression. |
| [TimeKeywordDetectorTest](../app/src/androidTest/java/lofit/TimeKeywordDetectorTest.kt) | 8 | Keep real bundled model/audio, false positives, reset, latency, noise and silence. |
| [TrendGraphViewTest](../app/src/androidTest/java/lofit/TrendGraphViewTest.kt) | 2 | Keep single/multiple-point geometry; wrappers already share helper, little merge benefit. |
| [VoiceTimerFlowTest](../app/src/androidTest/java/lofit/VoiceTimerFlowTest.kt) | 18 | Keep eligibility, manual mode and lifecycle; reduce duplicated audio work selectively. |
| [WorkoutBluetoothTest](../app/src/androidTest/java/lofit/WorkoutBluetoothTest.kt) | 1 | Keep radio-start smoke; strengthen stop claim; hardware assumptions may skip. |
| [WorkoutFlowTest](../app/src/androidTest/java/lofit/WorkoutFlowTest.kt) | 65 | Fold six narrow scenarios, trim styling, strengthen assertions and close activities. |
| [WorkoutPlanFileTest](../app/src/androidTest/java/lofit/WorkoutPlanFileTest.kt) | 4 | Move two codecs to JVM; keep provider/share grants and manifest resolution. |
| [WorkoutScaleReaderTest](../app/src/androidTest/java/lofit/WorkoutScaleReaderTest.kt) | 2 | Keep filter/error/stale handling; assert actual interruption and no synthetic readings. |
| [WorkoutSetScrollTest](../app/src/androidTest/java/lofit/WorkoutSetScrollTest.kt) | 9 | Keep visibility/obstruction and distinct manual, voice, notification and timer routes. |
| **Total** | **278** | **41 files** |

### Script tests and shared helpers

| File | Tests | Disposition |
| --- | ---: | --- |
| [test_sharded_runner.py](../scripts/tests/test_sharded_runner.py) | 5 | Keep discovery completeness, failure parsing, crash/truncation, duplicate/missing and skipped-result contracts. Strengthen error identities. |
| [clean-exercise-background.test.mjs](../scripts/tests/clean-exercise-background.test.mjs) | 5 | Keep actual pixel cleanup/detail preservation, masks/validation and CLI normalization/encoding/failure preservation. Distinct algorithm and shell boundaries. |
| **Total** | **10** | **2 files** |

[UiWaits.kt](../app/src/androidTest/java/lofit/UiWaits.kt) and [RepositoryTestStorage.kt](../app/src/androidTest/java/lofit/RepositoryTestStorage.kt) are helpers, not additional test suites. Retain bounded observable waits, keyboard monitoring, isolated repository storage and cleanup. Shared fixture work should build on them.
