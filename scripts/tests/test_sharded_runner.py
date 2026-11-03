import importlib.util
from pathlib import Path
import sys
import unittest

spec = importlib.util.spec_from_file_location("sharded_runner", Path(__file__).parents[1] / "test-sharded.py")
runner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)


def result(method, code=0, stack=""):
    return (f"INSTRUMENTATION_STATUS: class=lofit.SampleTest\n"
            f"INSTRUMENTATION_STATUS: test={method}\n"
            f"INSTRUMENTATION_STATUS: stack={stack}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


class ShardedRunnerTest(unittest.TestCase):
    def test_all_methods_must_run_exactly_once(self):
        expected = ["lofit.SampleTest#one", "lofit.SampleTest#two"]
        runner.validate_shards(expected, [[expected[0]], [expected[1]]])
        for shards in ([[expected[0]], [expected[0]]], [[expected[0]], []]):
            with self.assertRaises(RuntimeError):
                runner.validate_shards(expected, shards)

    def test_failure_is_detected_even_when_adb_exits_successfully(self):
        tests, errors = runner.parse_results(result("one", -2, "AssertionError\n at test.kt:12") +
                                            "INSTRUMENTATION_CODE: -1\n")
        self.assertEqual([], errors)
        self.assertEqual(-2, tests[0].code)
        self.assertIn("at test.kt:12", tests[0].stack)

    def test_crash_and_truncated_runs_are_errors(self):
        for ending in ("", "INSTRUMENTATION_CODE: 0\n",
                       "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: -1\n"):
            tests, errors = runner.parse_results(result("one") + ending)
            self.assertTrue(errors)

    def test_missing_and_duplicate_completions_are_errors(self):
        tests, errors = runner.parse_results(result("one") + result("one") +
                                            "INSTRUMENTATION_CODE: -1\n")
        self.assertEqual([], errors)
        errors = runner.validate_tests(tests, ["lofit.SampleTest#one", "lofit.SampleTest#two"])
        self.assertEqual(2, len(errors))

    def test_skipped_tests_remain_accounted_for(self):
        tests, errors = runner.parse_results(result("one", -3) + result("two", -4) +
                                            "INSTRUMENTATION_CODE: -1\n")
        self.assertEqual([], errors)
        self.assertEqual([], runner.validate_tests(tests, ["lofit.SampleTest#one", "lofit.SampleTest#two"]))
        self.assertEqual([-3, -4], [test.code for test in tests])


if __name__ == "__main__":
    unittest.main()
