"""Checks for missing measurements, long runs and safe report publication."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import gpu_memory_benchmark as memory
import speed_benchmark as speed


class SpeedMeasurementTest(unittest.TestCase):
    def test_missing_process_readings_are_not_zero(self):
        process = Mock(pid=123)
        process.children.return_value = []
        process.memory_full_info.side_effect = speed.psutil.AccessDenied(123)
        process.memory_info.side_effect = speed.psutil.AccessDenied(123)
        self.assertEqual(speed.process_memory(process), (None, {123}))
        process.memory_info.side_effect = None
        process.memory_info.return_value.rss = 0
        self.assertEqual(speed.process_memory(process), (0, {123}))
        process.memory_info.return_value.rss = 4096
        self.assertEqual(speed.process_memory(process), (4096, {123}))

    def test_measure_and_render_preserve_missing_versus_zero(self):
        for reading, expected in ((None, "n/a"), (0, "0.00 GiB")):
            with self.subTest(reading=reading), tempfile.TemporaryDirectory() as directory:
                process = Mock(pid=123)
                process.wait.return_value = process.poll.return_value = 0
                with patch.object(speed.subprocess, "Popen", return_value=process), \
                     patch.object(speed.psutil, "Process"), \
                     patch.object(speed, "process_memory", return_value=(reading, {123})):
                    result = speed.measure(Path("casanovo"), Path("config"), Path("input"),
                                           Path(directory), "cpu", "cpu")
                self.assertEqual(result.peak_host_gb, reading)
                self.assertIn(f"| {expected} |\n", speed.render([result], {}))

    def test_prediction_duration_supports_days(self):
        self.assertAlmostEqual(speed.prediction_seconds("Time Elapsed: 0:06:20.341548"), 380.341548)
        self.assertAlmostEqual(speed.prediction_seconds("Time Elapsed: 1 day, 01:02:03.5"), 90123.5)
        self.assertEqual(speed.prediction_seconds("Time Elapsed: 2 days, 00:00:00"), 172800)
        self.assertIsNone(speed.prediction_seconds("no completed report"))

    def test_publication_requires_cpu_and_gpu_memory_readings(self):
        for missing in (None, "cpu_ram", "gpu_ram", "gpu_device", "capped_device"):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                document = root / "performance.md"
                original = ("intro\n<!-- BENCHMARK:BEGIN -->\nold speed\n<!-- BENCHMARK:END -->\n"
                            "<!-- GPU-MEMORY:BEGIN -->\nold memory\n<!-- GPU-MEMORY:END -->\n")
                document.write_text(original, encoding="utf-8")
                context = dict(GPU="gpu", CPU="cpu", Casanovo="5", Model="model", Settings="defaults",
                               **{"Input SHA256": "input", "Model SHA256": "model"})
                baseline = dict(label="baseline", budget_gib=0, completed=True, matches_baseline=True,
                                outcome="Completed", psm_count=1, total_seconds=1,
                                prediction_seconds=1, peak_host_ram_gib=1, peak_gpu_mib=512,
                                gpu_per_process=True, gpu_baseline_mib=0,
                                peak_allocated_bytes=1024, exit_code=0)
                capped = {**baseline, "label": "cap", "budget_gib": 1}
                if missing == "gpu_ram":
                    baseline["peak_host_ram_gib"] = None
                elif missing == "gpu_device":
                    baseline["peak_gpu_mib"] = None
                elif missing == "capped_device":
                    capped["peak_gpu_mib"] = None
                (root / "memory-benchmark.json").write_text(
                    json.dumps(dict(context=context, results=[baseline, capped])), encoding="utf-8")
                cpu = speed.Result(device="cpu", hardware="cpu", spectra=1, total_seconds=1,
                                   predict_seconds=1, peak_host_gb=None if missing == "cpu_ram" else 1,
                                   peak_gpu_mib=None, gpu_per_process=True, gpu_baseline_mib=0,
                                   peak_gpu_tensors_mib=None, exit_code=0)
                args = SimpleNamespace(out_dir=root, update_doc=document, devices=["gpu", "cpu"],
                                       casanovo=Path("casanovo"), model=Path("model"), input=Path("input"))
                with patch.object(memory, "sweep", return_value=0), \
                     patch.object(speed, "make_config", return_value=Path("config")), \
                     patch.object(speed, "measure", return_value=cpu), \
                     patch.object(memory, "psm_fingerprint", return_value=(1, "hash")), \
                     contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    status = memory.release_benchmark(args, context)
                self.assertEqual(status, 0 if missing is None else 1)
                self.assertEqual(document.read_text(encoding="utf-8") == original, missing is not None)
                self.assertTrue((root / "benchmark.json").is_file())


if __name__ == "__main__":
    unittest.main()
