package lofit

internal data class HevyTemplate(
    val id: String,
    val title: String,
    val type: String,
    val custom: Boolean = false,
)

/** Match reviewed default exercises by stable Hevy IDs; uncertain matches use a placeholder. */
internal object HevyExerciseMapping {
    // IDs come from the bundled Hevy default catalog. Keep these matches compatible with metrics.
    internal val defaultTemplateIds =
        mapOf(
            "archer-push-up" to "392887AA", // Push Up
            "back-extension" to "A05C064D", // Back Extension (Machine)
            "back-squat" to "D04AC939", // Squat (Barbell)
            "bench-press" to "79D0BB3A", // Bench Press (Barbell)
            "cable-tricep-extension" to "21310F5F", // Triceps Extension (Cable)
            "clap-push-up" to "10D76E8F", // Clap Push Ups
            "close-grip-push-up" to "947DAC23", // Push Up - Close Grip
            "cossack-squat" to "D731CCA8", // Lateral Squat
            "dead-hang" to "B9380898", // Dead Hang
            "deadlift" to "C6272009", // Deadlift (Barbell)
            "decline-push-up" to "C43825EA", // Decline Push Up
            "diamond-push-up" to "6575F52D", // Diamond Push Up
            "dumbbell-curl" to "37FCC2BB", // Bicep Curl (Dumbbell)
            "3-4-sit-up" to "022DF610", // Sit Up
            "90-90-hamstring" to "527DA061", // Stretching
            "ab-crunch-machine" to "EB43ADD4", // Crunch (Machine)
            "ab-roller" to "99D5F10E", // Ab Wheel
            "adductor" to "527DA061", // Stretching
            "adductor-groin" to "527DA061", // Stretching
            "all-fours-quad-stretch" to "527DA061", // Stretching
            "alternate-hammer-curl" to "7E3BC8B6", // Hammer Curl (Dumbbell)
            "alternate-heel-touchers" to "567FB505", // Heel Taps
            "alternate-incline-dumbbell-curl" to "8BAB2735", // Seated Incline Curl (Dumbbell)
            "alternating-floor-press" to "756EE329", // Floor Press (Dumbbell)
            "alternating-hang-clean" to "BD4E7E53", // Hang Clean
            "alternating-kettlebell-press" to "6433CD93", // Kettlebell Shoulder Press
            "alternating-kettlebell-row" to "05487216", // Gorilla Row (Kettlebell)
            "alternating-renegade-row" to "1B89CA1B", // Renegade Row (Dumbbell)
            "ankle-circles" to "79EF4E4F", // Warm Up
            "ankle-on-the-knee" to "527DA061", // Stretching
            "anterior-tibialis-smr" to "527DA061", // Stretching
            "arm-circles" to "79EF4E4F", // Warm Up
            "arnold-dumbbell-press" to "A69FF221", // Arnold Press (Dumbbell)
            "around-the-worlds" to "D4A2FE7E", // Around The World
            "axle-deadlift" to "C6272009", // Deadlift (Barbell)
            "back-flyes-with-bands" to "E8D86EE8", // Band Pullaparts
            "barbell-bench-press-medium-grip" to "79D0BB3A", // Bench Press (Barbell)
            "barbell-curl" to "A5AC6449", // Bicep Curl (Barbell)
            "barbell-deadlift" to "C6272009", // Deadlift (Barbell)
            "barbell-full-squat" to "D04AC939", // Squat (Barbell)
            "barbell-glute-bridge" to "49C922A1", // Glute Bridge (Barbell)
            "barbell-guillotine-bench-press" to "79D0BB3A", // Bench Press (Barbell)
            "barbell-hack-squat" to "AC5A4C58", // Hack Squat
            "barbell-hip-thrust" to "D57C2EC7", // Hip Thrust (Barbell)
            "barbell-incline-bench-press-medium-grip" to
                "50DFDFAB", // Incline Bench Press (Barbell)
            "barbell-lunge" to "6E6EE645", // Lunge (Barbell)
            "barbell-rear-delt-row" to "55E6546F", // Bent Over Row (Barbell)
            "barbell-seated-calf-raise" to "062AB91A", // Seated Calf Raise
            "barbell-shoulder-press" to "7B8D84E8", // Overhead Press (Barbell)
            "barbell-shrug" to "0B841777", // Shrug (Barbell)
            "barbell-shrug-behind-the-back" to "0B841777", // Shrug (Barbell)
            "barbell-side-split-squat" to "0F24286A", // Bulgarian Split Squat (Barbell)
            "barbell-squat" to "D04AC939", // Squat (Barbell)
            "barbell-squat-to-a-bench" to "38FC1AB9", // Box Squat (Barbell)
            "barbell-walking-lunge" to "6E6EE645", // Lunge (Barbell)
            "behind-head-chest-stretch" to "527DA061", // Stretching
            "bench-dips" to "CD6DC8E5", // Bench Dip
            "bench-jump" to "56092DD1", // Box Jump
            "bench-press-powerlifting" to "79D0BB3A", // Bench Press (Barbell)
            "bench-press-with-chains" to "79D0BB3A", // Bench Press (Barbell)
            "bent-arm-dumbbell-pullover" to "67280085", // Pullover (Dumbbell)
            "bent-knee-hip-raise" to "08590920", // Hanging Knee Raise
            "bent-over-barbell-row" to "55E6546F", // Bent Over Row (Barbell)
            "bent-over-dumbbell-rear-delt-raise-with-head-on-bench" to
                "E5988A0A", // Rear Delt Reverse Fly (Dumbbell)
            "bent-over-one-arm-long-bar-row" to "D7D7FCCE", // Landmine Row
            "bent-over-two-arm-long-bar-row" to "D7D7FCCE", // Landmine Row
            "bent-over-two-dumbbell-row" to "23E92538", // Bent Over Row (Dumbbell)
            "bent-over-two-dumbbell-row-with-palms-in" to "23E92538", // Bent Over Row (Dumbbell)
            "board-press" to "79D0BB3A", // Bench Press (Barbell)
            "body-tricep-press" to "28BB4A95", // Triceps Dip
            "bodyweight-squat" to "9694DA61", // Squat (Bodyweight)
            "bodyweight-walking-lunge" to "5E1A7777", // Lunge
            "box-squat" to "F2FCE72D", // Box Squat
            "box-squat-with-bands" to "F1258206", // Squat (Band)
            "box-squat-with-chains" to "38FC1AB9", // Box Squat (Barbell)
            "brachialis-smr" to "527DA061", // Stretching
            "butt-lift-bridge" to "CDA23948", // Glute Bridge
            "butterfly" to "9DCE2D64", // Butterfly (Pec Deck)
            "cable-chest-press" to "99C1F2AD", // Bench Press (Cable)
            "cable-crossover" to "651F844C", // Cable Fly Crossovers
            "cable-crunch" to "23A48484", // Cable Crunch
            "cable-hammer-curls-rope-attachment" to "36E8F14E", // Hammer Curl (Cable)
            "cable-hip-adduction" to "22578A94", // Hip Adduction (Cable)
            "cable-incline-triceps-extension" to "21310F5F", // Triceps Extension (Cable)
            "cable-lying-triceps-extension" to "21310F5F", // Triceps Extension (Cable)
            "cable-one-arm-tricep-extension" to "552AB030", // Single Arm Triceps Pushdown (Cable)
            "cable-rear-delt-fly" to "C315DC2A", // Rear Delt Reverse Fly (Cable)
            "cable-reverse-crunch" to "23A48484", // Cable Crunch
            "cable-rope-overhead-triceps-extension" to
                "B5EFBF9C", // Overhead Triceps Extension (Cable)
            "cable-rope-rear-delt-rows" to "C315DC2A", // Rear Delt Reverse Fly (Cable)
            "cable-russian-twists" to "2982AA23", // Russian Twist (Weighted)
            "cable-seated-crunch" to "23A48484", // Cable Crunch
            "cable-seated-lateral-raise" to "BE289E45", // Lateral Raise (Cable)
            "cable-shrugs" to "FFABC123", // Shrug (Cable)
            "calf-press" to "91237BDD", // Calf Press (Machine)
            "calf-press-on-the-leg-press-machine" to "91237BDD", // Calf Press (Machine)
            "calf-stretch-elbows-against-wall" to "527DA061", // Stretching
            "calf-stretch-hands-against-wall" to "527DA061", // Stretching
            "calves-smr" to "527DA061", // Stretching
            "cat-stretch" to "527DA061", // Stretching
            "chair-leg-extended-stretch" to "527DA061", // Stretching
            "chair-lower-back-stretch" to "527DA061", // Stretching
            "chair-upper-body-stretch" to "527DA061", // Stretching
            "chest-and-front-of-shoulder-stretch" to "527DA061", // Stretching
            "chest-stretch-on-stability-ball" to "527DA061", // Stretching
            "chin-to-chest-stretch" to "527DA061", // Stretching
            "chin-up" to "29083183", // Chin Up
            "clean" to "ABB00838", // Clean
            "clean-and-jerk" to "9E09CEC3", // Clean and Jerk
            "clean-and-press" to "D3095577", // Clean and Press
            "clean-deadlift" to "C6272009", // Deadlift (Barbell)
            "clean-from-blocks" to "ABB00838", // Clean
            "clean-pull" to "652FEA39", // Clean Pull
            "clean-shrug" to "0B841777", // Shrug (Barbell)
            "clock-push-up" to "392887AA", // Push Up
            "close-grip-barbell-bench-press" to "35B51B87", // Bench Press - Close Grip (Barbell)
            "close-grip-dumbbell-press" to "3601968B", // Bench Press (Dumbbell)
            "close-grip-ez-bar-curl" to "01A35BF9", // EZ Bar Biceps Curl
            "close-grip-ez-bar-curl-with-band" to "01A35BF9", // EZ Bar Biceps Curl
            "close-grip-ez-bar-press" to "35B51B87", // Bench Press - Close Grip (Barbell)
            "close-grip-front-lat-pulldown" to "4E5257DE", // Lat Pulldown - Close Grip (Cable)
            "close-grip-push-up-off-of-a-dumbbell" to "947DAC23", // Push Up - Close Grip
            "close-grip-standing-barbell-curl" to "A5AC6449", // Bicep Curl (Barbell)
            "concentration-curls" to "724CDE60", // Concentration Curl
            "cross-body-crunch" to "A41C7261", // Bicycle Crunch
            "cross-body-hammer-curl" to "32C4D4A2", // Cross Body Hammer Curl
            "crunch-hands-overhead" to "DCF3B31B", // Crunch
            "crunch-legs-on-exercise-ball" to "DCF3B31B", // Crunch
            "crunches" to "DCF3B31B", // Crunch
            "dancers-stretch" to "527DA061", // Stretching
            "dead-bug" to "D8911FC4", // Dead Bug
            "deadlift-with-bands" to "99507114", // Deadlift (Band)
            "deadlift-with-chains" to "C6272009", // Deadlift (Barbell)
            "decline-barbell-bench-press" to "DA0F0470", // Decline Bench Press (Barbell)
            "decline-crunch" to "BC10A922", // Decline Crunch
            "decline-dumbbell-bench-press" to "18487FA7", // Decline Bench Press (Dumbbell)
            "decline-dumbbell-flyes" to "A351AED7", // Decline Chest Fly (Dumbbell)
            "decline-dumbbell-triceps-extension" to "3765684D", // Triceps Extension (Dumbbell)
            "decline-ez-bar-triceps-extension" to "2F8D3067", // Triceps Extension (Barbell)
            "decline-oblique-crunch" to "DBE341AA", // Oblique Crunch
            "decline-reverse-crunch" to "7952B5CD", // Reverse Crunch
            "decline-smith-press" to "FFC106CB", // Decline Bench Press (Smith Machine)
            "deficit-deadlift" to "C6272009", // Deadlift (Barbell)
            "dip-machine" to "5122E7D9", // Seated Dip Machine
            "dips-triceps-version" to "28BB4A95", // Triceps Dip
            "double-kettlebell-alternating-hang-clean" to "F99C211D", // Kettlebell Clean
            "double-kettlebell-push-press" to "6433CD93", // Kettlebell Shoulder Press
            "double-kettlebell-snatch" to "89304423", // Kettlebell Snatch
            "drag-curl" to "D950429E", // Drag Curl
            "dumbbell-alternate-bicep-curl" to "37FCC2BB", // Bicep Curl (Dumbbell)
            "dumbbell-bench-press" to "3601968B", // Bench Press (Dumbbell)
            "dumbbell-bench-press-with-neutral-grip" to "3601968B", // Bench Press (Dumbbell)
            "dumbbell-bicep-curl" to "37FCC2BB", // Bicep Curl (Dumbbell)
            "dumbbell-clean" to "ABB00838", // Clean
            "dumbbell-floor-press" to "756EE329", // Floor Press (Dumbbell)
            "dumbbell-flyes" to "12017185", // Chest Fly (Dumbbell)
            "dumbbell-incline-row" to "914F3A96", // Chest Supported Incline Row (Dumbbell)
            "dumbbell-lunges" to "B537D09F", // Lunge (Dumbbell)
            "dumbbell-lying-one-arm-rear-lateral-raise" to
                "E5988A0A", // Rear Delt Reverse Fly (Dumbbell)
            "dumbbell-lying-rear-lateral-raise" to "E5988A0A", // Rear Delt Reverse Fly (Dumbbell)
            "dumbbell-one-arm-shoulder-press" to "878CD1D0", // Shoulder Press (Dumbbell)
            "dumbbell-one-arm-triceps-extension" to
                "8347DFD1", // Single Arm Tricep Extension (Dumbbell)
            "dumbbell-one-arm-upright-row" to "797F0782", // Upright Row (Dumbbell)
            "dumbbell-prone-incline-curl" to "8BAB2735", // Seated Incline Curl (Dumbbell)
            "dumbbell-raise" to "8293E554", // Front Raise (Dumbbell)
            "dumbbell-rear-lunge" to "FFDA283B", // Reverse Lunge (Dumbbell)
            "dumbbell-seated-one-leg-calf-raise" to "062AB91A", // Seated Calf Raise
            "dumbbell-shoulder-press" to "878CD1D0", // Shoulder Press (Dumbbell)
            "dumbbell-shrug" to "ABEC557F", // Shrug (Dumbbell)
            "dumbbell-side-bend" to "026FD047", // Side Bend (Dumbbell)
            "dumbbell-squat" to "DCFF3E9F", // Squat (Dumbbell)
            "dumbbell-squat-to-a-bench" to "DCFF3E9F", // Squat (Dumbbell)
            "dumbbell-step-ups" to "BF6ECE89", // Dumbbell Step Up
            "dumbbell-tricep-extension-pronated-grip" to "3765684D", // Triceps Extension (Dumbbell)
            "dynamic-back-stretch" to "527DA061", // Stretching
            "dynamic-chest-stretch" to "527DA061", // Stretching
            "elbow-circles" to "79EF4E4F", // Warm Up
            "elbow-to-knee" to "A01BE509", // Elbow to Knee
            "elbows-back" to "79EF4E4F", // Warm Up
            "ez-bar-curl" to "01A35BF9", // EZ Bar Biceps Curl
            "ez-bar-skullcrusher" to "875F585F", // Skullcrusher (Barbell)
            "face-pull" to "BE640BA0", // Face Pull
            "flat-bench-cable-flyes" to "651F844C", // Cable Fly Crossovers
            "flat-bench-leg-pull-in" to "BD5935CF", // Lying Knee Raise
            "flat-bench-lying-leg-raise" to "09C9F635", // Lying Leg Raise
            "flexor-incline-dumbbell-curls" to "8BAB2735", // Seated Incline Curl (Dumbbell)
            "floor-press" to "D53F2886", // Floor Press (Barbell)
            "floor-press-with-chains" to "D53F2886", // Floor Press (Barbell)
            "flutter-kicks" to "2FA77B28", // Flutter Kicks
            "foot-smr" to "527DA061", // Stretching
            "frankenstein-squat" to "5046D0A9", // Front Squat
            "freehand-jump-squat" to "70D4EBBF", // Jump Squat
            "frog-hops" to "79EF4E4F", // Warm Up
            "frog-sit-ups" to "022DF610", // Sit Up
            "front-barbell-squat" to "5046D0A9", // Front Squat
            "front-barbell-squat-to-a-bench" to "5046D0A9", // Front Squat
            "front-cable-raise" to "DBB91A3C", // Front Raise (Cable)
            "front-dumbbell-raise" to "8293E554", // Front Raise (Dumbbell)
            "front-incline-dumbbell-raise" to "8293E554", // Front Raise (Dumbbell)
            "front-leg-raises" to "79EF4E4F", // Warm Up
            "front-squat-clean-grip" to "5046D0A9", // Front Squat
            "front-squats-with-two-kettlebells" to "A127DA73", // Kettlebell Goblet Squat
            "full-range-of-motion-lat-pulldown" to "6A6C31A5", // Lat Pulldown (Cable)
            "glute-kickback" to "987234AB", // Glute Kickback on Floor
            "groin-and-back-stretch" to "527DA061", // Stretching
            "groiners" to "527DA061", // Stretching
            "hack-squat" to "AC5A4C58", // Hack Squat
            "hamstring-smr" to "527DA061", // Stretching
            "hamstring-stretch" to "527DA061", // Stretching
            "handstand-push-ups" to "90B04F96", // Handstand Push Up
            "hanging-leg-raise" to "F8356514", // Hanging Leg Raise
            "hanging-pike" to "F8356514", // Hanging Leg Raise
            "high-cable-curls" to "582ADA23", // Overhead Curl (Cable)
            "hip-circles-prone" to "79EF4E4F", // Warm Up
            "hug-a-ball" to "79EF4E4F", // Warm Up
            "hug-knees-to-chest" to "79EF4E4F", // Warm Up
            "hyperextensions-with-no-hyperextension-bench" to
                "091737FA", // Back Extension (Weighted Hyperextension)
            "iliotibial-tract-smr" to "527DA061", // Stretching
            "inchworm" to "79EF4E4F", // Warm Up
            "incline-push-up" to "39C99849", // Incline Push Ups
            "incline-push-up-close-grip" to "947DAC23", // Push Up - Close Grip
            "incline-push-up-medium" to "39C99849", // Incline Push Ups
            "incline-push-up-reverse-grip" to "39C99849", // Incline Push Ups
            "incline-push-up-wide" to "39C99849", // Incline Push Ups
            "intermediate-groin-stretch" to "527DA061", // Stretching
            "intermediate-hip-flexor-and-quad-stretch" to "527DA061", // Stretching
            "iron-crosses-stretch" to "527DA061", // Stretching
            "isometric-neck-exercise-front-and-back" to "527DA061", // Stretching
            "isometric-neck-exercise-sides" to "527DA061", // Stretching
            "it-band-and-glute-stretch" to "527DA061", // Stretching
            "jackknife-sit-up" to "32B0B590", // Jackknife Sit Up
            "janda-sit-up" to "022DF610", // Sit Up
            "kettlebell-one-legged-deadlift" to
                "937292AB", // Single Leg Romanian Deadlift (Dumbbell)
            "kettlebell-turkish-get-up-lunge-style" to "5F8903BF", // Kettlebell Turkish Get Up
            "knee-across-the-body" to "527DA061", // Stretching
            "knee-circles" to "79EF4E4F", // Warm Up
            "knee-tuck-jump" to "56092DD1", // Box Jump
            "kneeling-cable-crunch-with-alternating-oblique-twists" to "23A48484", // Cable Crunch
            "kneeling-cable-triceps-extension" to "21310F5F", // Triceps Extension (Cable)
            "kneeling-forearm-stretch" to "527DA061", // Stretching
            "kneeling-high-pulley-row" to "D82EA543", // Kneeling Pulldown (band)
            "kneeling-single-arm-high-pulley-row" to "D0C4A899", // Single Arm Cable Row
            "latissimus-dorsi-smr" to "527DA061", // Stretching
            "leg-extensions" to "75A4F6C4", // Leg Extension (Machine)
            "leg-lift" to "09C9F635", // Lying Leg Raise
            "leg-press" to "C7973E0E", // Leg Press (Machine)
            "leg-up-hamstring-stretch" to "527DA061", // Stretching
            "leverage-chest-press" to "7EB3F7C3", // Chest Press (Machine)
            "leverage-decline-chest-press" to "FAF31231", // Decline Bench Press (Machine)
            "leverage-high-row" to "BC3492DA", // Iso-Lateral High Row (Machine)
            "leverage-incline-chest-press" to "FBF92739", // Incline Chest Press (Machine)
            "leverage-iso-row" to "91FAFBA3", // Iso-Lateral Low Row
            "leverage-shoulder-press" to "059E835D", // Shoulder Press (Machine Plates)
            "leverage-shrug" to "19A38071", // Shrug (Machine)
            "looking-at-ceiling" to "79EF4E4F", // Warm Up
            "low-cable-crossover" to "293483AD", // Low Cable Fly Crossovers
            "low-cable-triceps-extension" to "21310F5F", // Triceps Extension (Cable)
            "lower-back-curl" to "527DA061", // Stretching
            "lower-back-smr" to "527DA061", // Stretching
            "lying-bent-leg-groin" to "527DA061", // Stretching
            "lying-crossover" to "527DA061", // Stretching
            "lying-glute" to "527DA061", // Stretching
            "lying-hamstring" to "527DA061", // Stretching
            "lying-leg-curls" to "B8127AD1", // Lying Leg Curl (Machine)
            "lying-machine-squat" to "CC35A01F", // Squat (Machine)
            "lying-one-arm-lateral-raise" to "422B08F1", // Lateral Raise (Dumbbell)
            "lying-prone-quadriceps" to "527DA061", // Stretching
            "lying-t-bar-row" to "08A2974E", // T Bar Row
            "machine-bench-press" to "0FBF7195", // Bench Press (Smith Machine)
            "machine-bicep-curl" to "AF328E3D", // Bicep Curl (Machine)
            "machine-preacher-curls" to "1E9A6B8E", // Preacher Curl (Machine)
            "machine-shoulder-military-press" to "059E835D", // Shoulder Press (Machine Plates)
            "machine-triceps-extension" to "3092FADD", // Triceps Extension (Machine)
            "middle-back-stretch" to "527DA061", // Stretching
            "narrow-stance-hack-squats" to "1E42FD5F", // Hack Squat (Machine)
            "narrow-stance-leg-press" to "C7973E0E", // Leg Press (Machine)
            "neck-smr" to "527DA061", // Stretching
            "on-your-back-quad-stretch" to "527DA061", // Stretching
            "on-your-side-quad-stretch" to "527DA061", // Stretching
            "one-arm-against-wall" to "527DA061", // Stretching
            "one-arm-dumbbell-row" to "F1E57334", // Dumbbell Row
            "one-arm-flat-bench-dumbbell-flye" to "12017185", // Chest Fly (Dumbbell)
            "one-arm-incline-lateral-raise" to "422B08F1", // Lateral Raise (Dumbbell)
            "one-arm-kettlebell-clean" to "F99C211D", // Kettlebell Clean
            "one-arm-kettlebell-clean-and-jerk" to "9E09CEC3", // Clean and Jerk
            "one-arm-kettlebell-floor-press" to "756EE329", // Floor Press (Dumbbell)
            "one-arm-kettlebell-push-press" to "6433CD93", // Kettlebell Shoulder Press
            "one-arm-kettlebell-row" to "05487216", // Gorilla Row (Kettlebell)
            "one-arm-kettlebell-snatch" to "89304423", // Kettlebell Snatch
            "one-arm-kettlebell-split-snatch" to "89304423", // Kettlebell Snatch
            "one-arm-kettlebell-swings" to "F8A0FCCA", // Kettlebell Swing
            "one-arm-lat-pulldown" to "2EE45F81", // Single Arm Lat Pulldown
            "one-arm-long-bar-row" to "D7D7FCCE", // Landmine Row
            "one-handed-hang" to "B9380898", // Dead Hang
            "one-knee-to-chest" to "527DA061", // Stretching
            "one-legged-cable-kickback" to "ACB2751D", // Standing Cable Glute Kickbacks
            "overhead-cable-curl" to "582ADA23", // Overhead Curl (Cable)
            "overhead-lat" to "527DA061", // Stretching
            "overhead-stretch" to "527DA061", // Stretching
            "overhead-triceps" to "527DA061", // Stretching
            "pallof-press" to "CC55119B", // Cable Core Pallof Press
            "pelvic-tilt-into-bridge" to "79EF4E4F", // Warm Up
            "peroneals-smr" to "527DA061", // Stretching
            "pull-through" to "8C331CD8", // Cable Pull Through
            "push-up-to-side-plank" to "E3EDA509", // Side Plank
            "reverse-cable-curl" to "9F48F858", // Reverse Curl (Cable)
            "reverse-grip-triceps-pushdown" to "8B5BED30", // Reverse Grip Triceps Pushdown
            "reverse-hyperextension" to "7C0028C3", // Reverse Hyperextension
            "reverse-machine-flyes" to "D8281C62", // Rear Delt Reverse Fly (Machine)
            "rope-crunch" to "23A48484", // Cable Crunch
            "side-bridge" to "E3EDA509", // Side Plank
            "incline-dumbbell-bench-press" to "07B38369", // Incline Bench Press (Dumbbell)
            "knee-push-up" to "B74A95BB", // Kneeling Push Up
            "l-sit-hold" to "6D54A050", // L-Sit Hold
            "leg-raise" to "09C9F635", // Lying Leg Raise
            "one-arm-push-up" to "B140095E", // One Arm Push Up
            "overhead-press" to "7B8D84E8", // Overhead Press (Barbell)
            "pike-push-up" to "0EFE8162", // Pike Pushup
            "pistol-squat" to "3FF6A22E", // Pistol Squat
            "plank" to "C6C9B8A0", // Plank
            "pull-up" to "1B2B1E7C", // Pull Up
            "push-up" to "392887AA", // Push Up
            "seated-leg-raise" to "0482DA98", // Leg Raise Parallel Bars
            "spiderman-push-up" to "392887AA", // Push Up
            "wall-push-up" to "392887AA", // Push Up
            "wall-sit" to "C8706C80", // Wall Sit
            "wide-grip-push-up" to "392887AA", // Push Up
        )

    private val aliases =
        mapOf(
            "back-squat" to "Squat (Barbell)",
            "bench-press" to "Bench Press (Barbell)",
            "incline-dumbbell-bench-press" to "Incline Bench Press (Dumbbell)",
            "cable-tricep-extension" to "Triceps Extension (Cable)",
            "deadlift" to "Deadlift (Barbell)",
            "overhead-press" to "Shoulder Press (Barbell)",
            "dumbbell-curl" to "Bicep Curl (Dumbbell)",
            "pull-up" to "Pull Up",
            "push-up" to "Push Up",
        )

    fun type(exercise: Exercise): String =
        when {
            exercise.type.tracksDuration && exercise.weighted -> "weight_duration"
            exercise.type.tracksDuration -> "duration"
            exercise.weighted -> "weight_reps"
            else -> "reps_only"
        }

    fun placeholder(type: String): String =
        when (type) {
            "duration" -> "Placeholder Timed"
            "weight_duration" -> "Placeholder Weighted Timed"
            "weight_reps" -> "Placeholder Weighted"
            else -> "Placeholder Reps"
        }

    fun find(
        exercise: Exercise,
        sets: List<LoggedSet>,
        templates: List<HevyTemplate>,
    ): HevyTemplate? {
        val expectedType = type(exercise)
        defaultTemplateIds[exercise.id]?.let { id ->
            templates
                .firstOrNull { !it.custom && it.id == id && compatible(it, expectedType, sets) }
                ?.let {
                    return it
                }
        }
        // LEGACY: Name matching still handles custom exercises and older template lists.
        val name = aliases[exercise.id] ?: exercise.name
        val names = buildList {
            add(name)
            val equipment =
                listOf("Dumbbell", "Barbell", "Kettlebell", "Cable", "Machine").firstOrNull {
                    name.startsWith("$it ", ignoreCase = true)
                }
            if (equipment != null) add("${name.drop(equipment.length + 1)} ($equipment)")
        }
        return templates
            .sortedBy { it.custom }
            .firstOrNull { template ->
                names.any { normalize(template.title) == normalize(it) } &&
                    compatible(template, expectedType, sets)
            }
    }

    private fun compatible(template: HevyTemplate, expectedType: String, sets: List<LoggedSet>) =
        template.type == expectedType ||
            (expectedType == "weight_reps" &&
                template.type in setOf("bodyweight_reps", "reps_only") &&
                sets.all { it.weightKg == 0.0 }) ||
            (expectedType == "reps_only" && template.type == "bodyweight_reps") ||
            (expectedType == "weight_duration" &&
                template.type == "duration" &&
                sets.all { it.weightKg == 0.0 })

    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}
