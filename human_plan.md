# Lofit

Lofit is supposed to be an android native local-only fitness app.

## Features

* Exercise types
  * Weighted Hold (timed, weight 0 possible)
  * Weighted Reps (weight 0 possible, meaning bodyweight)
  * Assymetrical exercise (be able to define left right for each workout entry)
* Set Types
  * Warmup
  * Normal
  * Failure
* Default Exercises (including AI generated thumbnails for each exercise)
  * Muscle Group Data
    * Primary muscle
    * Secondary Muscle
* Empty Workouts (add exercise on the fly)
  * Allow reordering exercises on the fly
* Pause timers
  * User defined default per workout
  * User defined default per exercise
  * Overrideable per exercise
* Sound cues
  * Pause over
  * Minimum desired hold time reached
* Saved Workout-Routines (Also allow adding exercises on the fly)
* Custom Exercises
* Workout History

## Low Priority Features

* Record-Tracking
  * Max hold time / weight
  * Max volume in one exercise (all sets)
* Workout Calendar (Past and planned)
* Open AI integration
  * Generation of goal oriented workouts and training plans (calendar)
  * Generation of images for exercises
  * Open AI subscription account, not business API

## Technical Goals

* Android 16+
  * Use up-to-date APIs and GUI elements where possible
* Kotlin
* Little to no external dependencies
* Test coverage (including UI workflows)
  * Tests should run fast while covering all potentially brittle components
* No animations

## How to test changes

* Android CLI emulator for manual testing
* Unit tests that drive android cli and make assertions on the UI state
