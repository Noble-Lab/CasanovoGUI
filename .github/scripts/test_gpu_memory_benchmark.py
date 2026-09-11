"""Fast, CPU-only checks for release-report persistence and output comparisons."""
import tempfile
import unittest
from pathlib import Path

from gpu_memory_benchmark import psm_fingerprint, replace_doc_block, render


class MemoryReportTest(unittest.TestCase):
    def test_psm_comparison_ignores_metadata_but_detects_score_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "result.mztab"
            path.write_bytes(b"MTD\tversion\t1\nPSM\tPEPTIDE\t0.9\n")
            original = psm_fingerprint(path)
            path.write_bytes(b"MTD\tversion\t2\r\nPSM\tPEPTIDE\t0.9\r\n")
            self.assertEqual(original, psm_fingerprint(path))
            path.write_bytes(b"MTD\tversion\t2\nPSM\tPEPTIDE\t0.8\n")
            self.assertNotEqual(original, psm_fingerprint(path))
            path.write_bytes(b"MTD\tversion\t2\n")
            self.assertEqual((0, None), psm_fingerprint(path))

    def test_update_preserves_other_release_sections(self):
        text = ("intro\n<!-- BENCHMARK:BEGIN -->\nold speed\n<!-- BENCHMARK:END -->\n"
                "instructions\n<!-- GPU-MEMORY:BEGIN -->\nold memory\n<!-- GPU-MEMORY:END -->\nhistory\n")
        updated = replace_doc_block(text, "new speed\n", "<!-- BENCHMARK:BEGIN -->", "<!-- BENCHMARK:END -->")
        updated = replace_doc_block(updated, "new memory\n")
        self.assertEqual(text.replace("old speed", "new speed").replace("old memory", "new memory"), updated)

    def test_bad_markers_are_rejected_without_silently_truncating_document(self):
        for text in ("no markers", "<!-- GPU-MEMORY:END --><!-- GPU-MEMORY:BEGIN -->",
                     "<!-- GPU-MEMORY:BEGIN --><!-- GPU-MEMORY:BEGIN --><!-- GPU-MEMORY:END -->"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                replace_doc_block(text, "new data")

    def test_oom_and_mismatched_outputs_do_not_count_as_successful_budgets(self):
        context = dict(Benchmarked="date", Input="input", GPU="gpu", **{
            "Operating system": "os", "Casanovo": "5", "PyTorch": "2", "Model": "model"})
        base = dict(label="run", budget_gib=1, outcome="CUDA OOM", total_seconds=1,
                    prediction_seconds=None, peak_allocated_bytes=None, peak_reserved_bytes=None,
                    peak_gpu_mib=None, psm_count=3, matches_baseline=False, completed=False,
                    gpu_per_process=False, gpu_baseline_mib=None)
        mismatch = {**base, "budget_gib": 2, "completed": True, "outcome": "Output mismatch"}
        report = render(context, [base, mismatch])
        self.assertNotIn("Lowest tested successful", report)
        success = {**base, "budget_gib": 3, "completed": True,
                   "matches_baseline": True, "outcome": "Completed"}
        report = render(context, [base, mismatch, success])
        self.assertIn("Lowest tested successful PyTorch limit: 3 GiB", report)
        self.assertIn("GPU memory readings were unavailable", report)

    def test_limit_summary_reports_device_peak_from_matching_successes_only(self):
        base = dict(label="run", budget_gib=3.5, outcome="Completed", total_seconds=500,
                    peak_gpu_mib=3914, psm_count=86311, matches_baseline=True,
                    completed=True, gpu_per_process=False, gpu_baseline_mib=0)
        results = [base, {**base, "peak_gpu_mib": 3886},
                   {**base, "budget_gib": 4, "peak_gpu_mib": 4328},
                   {**base, "outcome": "Output mismatch", "matches_baseline": False,
                    "peak_gpu_mib": 8192}]
        report = render({}, results)
        self.assertIn("3.5 GiB (2 complete runs)", report)
        self.assertIn("Observed GPU memory at that limit reached 3.82 GiB", report)
        self.assertIn("does not verify that a physical card", report)


if __name__ == "__main__":
    unittest.main()
