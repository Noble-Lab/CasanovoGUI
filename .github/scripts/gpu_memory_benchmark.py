"""GPU budget sweep implementation, invoked through speed_benchmark.py.

The private worker entry point executes in the Python environment beside the
selected Casanovo executable, so the allocator limit applies to Casanovo itself.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import shutil
import sys

BEGIN = "<!-- GPU-MEMORY:BEGIN -->"
END = "<!-- GPU-MEMORY:END -->"


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 ** 2), b""):
            digest.update(block)
    return digest.hexdigest()


def psm_fingerprint(path: Path) -> tuple[int, str | None]:
    """Hash complete PSM rows, excluding metadata and normalizing line endings."""
    count = 0
    digest = hashlib.sha256()
    if path.is_file():
        with path.open("rb") as stream:
            for line in stream:
                if line.startswith(b"PSM\t"):
                    count += 1
                    digest.update(line.rstrip(b"\r\n"))
    return count, digest.hexdigest() if count else None


def replace_doc_block(text: str, report: str, begin: str = BEGIN, end_marker: str = END) -> str:
    if text.count(begin) != 1 or text.count(end_marker) != 1:
        raise ValueError(f"Document must contain exactly one {begin} / {end_marker} pair")
    start = text.index(begin) + len(begin)
    end = text.index(end_marker)
    if end < start:
        raise ValueError("GPU-MEMORY markers are out of order")
    return text[:start] + "\n" + report.rstrip() + "\n" + text[end:]


def worker(request_path: Path) -> None:
    import importlib.metadata
    import torch
    import yaml

    request = json.loads(request_path.read_text(encoding="utf-8"))
    out = request_path.parent
    budget = request["budget_gib"]
    telemetry = {"budget_gib": budget, "oom": False}
    try:
        if torch.cuda.device_count() != 1:
            raise ValueError("The memory-budget test requires exactly one visible CUDA GPU")
        config = yaml.safe_load((out / "config.yaml").read_text(encoding="utf-8"))
        if config.get("accelerator") != "gpu" or config.get("devices") not in (None, 1, -1):
            raise ValueError("Use accelerator: gpu and a single-device configuration")
        total = torch.cuda.get_device_properties(0).total_memory
        if budget:
            if budget * 1024 ** 3 > total:
                raise ValueError(f"Budget {budget:g} GiB exceeds this GPU's capacity")
            torch.cuda.set_per_process_memory_fraction(budget * 1024 ** 3 / total, 0)
        telemetry.update(
            gpu=torch.cuda.get_device_name(0), total_bytes=total,
            allocator_backend=torch.cuda.get_allocator_backend(),
            allocator_fraction=budget * 1024 ** 3 / total if budget else 1.0,
            cuda=torch.version.cuda, cudnn=torch.backends.cudnn.version(),
            python=sys.version, executable=sys.executable,
            packages={d.metadata["Name"]: d.version for d in importlib.metadata.distributions()
                      if d.metadata["Name"]})
        write_json(out / "startup.json", telemetry)
        from casanovo.casanovo import main
        sys.argv = request["command"]
        main()
    except SystemExit as exc:
        telemetry["exit_code"] = exc.code
        raise
    except Exception as exc:
        telemetry.update(oom=isinstance(exc, torch.OutOfMemoryError),
                         exception={"type": type(exc).__name__, "message": str(exc)})
        raise
    finally:
        if torch.cuda.is_initialized():
            telemetry.update(peak_allocated_bytes=torch.cuda.max_memory_allocated(0),
                             peak_reserved_bytes=torch.cuda.max_memory_reserved(0),
                             memory_stats=dict(torch.cuda.memory_stats(0)))
        write_json(out / "torch_memory.json", telemetry)


def render(context: dict, results: list[dict]) -> str:
    lines = ["## GPU memory budget test", ""]
    for key in ("Benchmarked", "Input", "GPU", "Operating system", "Casanovo", "CasanovoGUI", "PyTorch", "Model", "Settings"):
        if key in context:
            lines.append(f"- **{key}:** {context[key]}")
    lines += ["", "Each run uses the same input, model and settings in a new process.",
              "The PyTorch limit covers tensors and cached memory. Peak GPU memory also includes CUDA overhead.", "",
              "| PyTorch limit (GiB) | Outcome | Total time (s) | Peak GPU memory (GiB) | PSM rows | Matches baseline |",
              "|---:|---|---:|---:|---:|---|"]
    def number(value, divisor=1):
        return f"{value / divisor:,.1f}" if value is not None else "n/a"
    for r in results:
        match = "Yes" if r["matches_baseline"] else ("No" if r["completed"] else "n/a")
        gpu = f"{r['peak_gpu_mib'] / 1024:.2f}" if r['peak_gpu_mib'] is not None else "n/a"
        lines.append(f"| {r['budget_gib'] or 'Unrestricted'} | {r['outcome']} | "
                     f"{number(r['total_seconds'])} | {gpu} | {r['psm_count']:,} | {match} |")
    successes = [r for r in results if r["budget_gib"] and r["completed"] and r["matches_baseline"]]
    if successes:
        minimum = min(r["budget_gib"] for r in successes)
        count = sum(r["budget_gib"] == minimum for r in successes)
        noun = "run" if count == 1 else "runs"
        lines += ["", f"**Lowest tested successful PyTorch limit: {minimum:g} GiB ({count} complete {noun}).**"]
        peaks = [r["peak_gpu_mib"] for r in successes if r["budget_gib"] == minimum]
        if all(peak is not None for peak in peaks):
            lines.append(f"**Observed GPU memory at that limit reached {max(peaks) / 1024:.2f} GiB.**")
        else:
            lines.append("GPU memory readings were unavailable for some runs at that limit.")
        lines.append("A budget test does not verify that a physical card of that size can run the job.")
    lines += ["", "\"Matches baseline\" compares every PSM row with the unrestricted run.",
              "OOM means out of memory; those row counts are partial results.", "",
              "GPU memory is sampled about every two seconds; brief peaks may be missed."]
    fallback = [r for r in results if not r["gpu_per_process"]]
    if fallback:
        baselines = {r["gpu_baseline_mib"] for r in fallback}
        if len(baselines) == 1 and None not in baselines:
            lines.append(f"Device readings subtract starting usage ({next(iter(baselines)):,} MiB in these runs).")
        else:
            lines.append("Device readings subtract each run's starting usage, recorded in the JSON results.")
        lines.append("Other GPU activity can affect these readings.")
    return "\n".join(lines) + "\n"


def sweep(args, context: dict) -> int:
    import speed_benchmark as benchmark

    if any(args.out_dir.iterdir()):
        raise SystemExit("Memory-budget tests require an empty --out-dir to preserve prior results")
    if len(benchmark._run(["nvidia-smi", "--query-gpu=uuid", "--format=csv,noheader"]).splitlines()) != 1:
        raise SystemExit("Memory-budget tests currently require one NVIDIA GPU")
    config = args.config.resolve() if args.config else benchmark.make_config(
        args.casanovo, "gpu", args.out_dir, context["Casanovo"])
    model = args.model.resolve()
    context = {**context, "Model": model.name, "Settings": benchmark.config_summary(config),
               "Input SHA256": file_hash(args.input), "Model SHA256": file_hash(model),
               "Config SHA256": file_hash(config)}
    python = args.casanovo.parent / ("python.exe" if sys.platform == "win32" else "python")
    results = []
    baseline_hash = None
    baseline_packages = None
    for index, budget in enumerate([0.0] + args.gpu_memory_budgets):
        label = f"{index:02d}-" + (f"{budget:g}GiB" if budget else "baseline")
        out = args.out_dir / label
        out.mkdir()
        shutil.copyfile(config, out / "config.yaml")
        command = ["casanovo", "sequence", "--config", str(out / "config.yaml"),
                   "--model", str(model), "--force_overwrite", "--output_dir", str(out),
                   "--output_root", "result", str(args.input)]
        write_json(out / "request.json", {"budget_gib": budget, "command": command})
        print(f"\n=== {label} ===", file=sys.stderr, flush=True)
        measured = benchmark.measure(
            args.casanovo, out / "config.yaml", args.input, out, "gpu", context["GPU"],
            command=[str(python), str(Path(__file__).resolve()), str(out / "request.json")],
            env_overrides={"PYTORCH_ALLOC_CONF": None, "PYTORCH_CUDA_ALLOC_CONF": None,
                           "CUDA_VISIBLE_DEVICES": None, "PYTHONIOENCODING": "utf-8",
                           "PYTHONUNBUFFERED": "1", "NO_COLOR": "1", "MPLBACKEND": "Agg",
                           "MPLCONFIGDIR": str(args.out_dir / "matplotlib-cache")},
            record_samples=True)
        telemetry_path = out / "torch_memory.json"
        telemetry = json.loads(telemetry_path.read_text(encoding="utf-8")) if telemetry_path.exists() else {}
        count, digest = psm_fingerprint(out / "result.mztab")
        log = (out / "run.log").read_text(encoding="utf-8", errors="replace")
        sequenced = re.search(r"Sequenced (\d+) spectra", log)
        complete = (measured.exit_code == 0 and measured.predict_seconds is not None
                    and sequenced is not None and count > 0 and count == int(sequenced[1])
                    and telemetry.get("peak_allocated_bytes") is not None)
        if index == 0 and complete:
            baseline_hash = digest
            baseline_packages = telemetry["packages"]
            context["Packages"] = baseline_packages
            context["Allocator"] = telemetry["allocator_backend"]
        recorded_packages = telemetry.get("packages")
        same_environment = recorded_packages == baseline_packages
        matches = bool(complete and baseline_hash and digest == baseline_hash and same_environment)
        stats = telemetry.get("memory_stats", {})
        outcome = "Completed" if matches else ("Output mismatch" if complete else
                  ("CUDA OOM" if telemetry.get("oom") else "Error"))
        # A run that died before recording its packages is a crash, not an environment change.
        if index > 0 and recorded_packages is not None and not same_environment:
            outcome = "Environment changed"
        result = dict(label=label, budget_gib=budget, outcome=outcome, completed=complete,
                      matches_baseline=matches, exit_code=measured.exit_code,
                      total_seconds=measured.total_seconds, prediction_seconds=measured.predict_seconds,
                      peak_allocated_bytes=telemetry.get("peak_allocated_bytes"),
                      peak_reserved_bytes=telemetry.get("peak_reserved_bytes"),
                      peak_gpu_mib=measured.peak_gpu_mib, gpu_per_process=measured.gpu_per_process,
                      peak_host_ram_gib=measured.peak_host_gb,
                      gpu_baseline_mib=measured.gpu_baseline_mib,
                      psm_count=count, psm_sha256=digest, allocator_ooms=stats.get("num_ooms"),
                      allocator_retries=stats.get("num_alloc_retries"), exception=telemetry.get("exception"))
        results.append(result)
        write_json(out / "result.json", result)
        write_json(args.out_dir / "memory-benchmark.json", {"context": context, "results": results})
        report = render(context, results)
        (args.out_dir / "memory-benchmark.md").write_text(report, encoding="utf-8")
        print(f"{label}: {outcome}, {measured.total_seconds:.1f} s, {count:,} PSM rows",
              file=sys.stderr, flush=True)
        if outcome not in ("Completed", "CUDA OOM") or (index == 0 and not matches):
            print(f"Stopped: inspect {out / 'run.log'}", file=sys.stderr)
            return 1
    # OOM is an expected measurement, but publishing requires a successful capped run.
    valid = any(r["budget_gib"] and r["matches_baseline"] for r in results)
    return 0 if valid else 1


def release_benchmark(args, context: dict) -> int:
    """One versioned report: GPU baseline + budgets, then the other requested devices."""
    from dataclasses import asdict
    import speed_benchmark as benchmark

    if args.update_doc:
        text = args.update_doc.read_text(encoding="utf-8")
        replace_doc_block(text, "")
        replace_doc_block(text, "", "<!-- BENCHMARK:BEGIN -->", "<!-- BENCHMARK:END -->")
    status = sweep(args, context)
    artifact = args.out_dir / "memory-benchmark.json"
    if not artifact.is_file():
        return 1
    data = json.loads(artifact.read_text(encoding="utf-8"))
    memory_results = data["results"]
    base = memory_results[0]
    results = []
    if base["completed"]:
        results.append(benchmark.Result(
            device="gpu", hardware=context["GPU"], spectra=base["psm_count"],
            total_seconds=base["total_seconds"], predict_seconds=base["prediction_seconds"],
            peak_host_gb=base["peak_host_ram_gib"], peak_gpu_mib=base["peak_gpu_mib"],
            gpu_per_process=base["gpu_per_process"], gpu_baseline_mib=base["gpu_baseline_mib"],
            peak_gpu_tensors_mib=round(base["peak_allocated_bytes"] / 1024 ** 2), exit_code=base["exit_code"]))
    config_source = args.out_dir / base["label"] / "config.yaml"
    context = {**context, "Model": data["context"]["Model"],
               "Settings": data["context"]["Settings"],
               "Input SHA256": data["context"]["Input SHA256"],
               "Model SHA256": data["context"]["Model SHA256"]}
    # Still measure CPU speed when every capped budget OOMs: it remains useful in a
    # release report. Other failures indicate a broken setup and stop further runs.
    can_continue = all(r["outcome"] in ("Completed", "CUDA OOM") for r in memory_results) and base["completed"]
    if can_continue:
        for device in dict.fromkeys(args.devices):
            if device == "gpu":
                continue
            config = benchmark.make_config(args.casanovo, device, args.out_dir,
                                           context["Casanovo"], config_source)
            out = args.out_dir / device
            print(f"\n=== {device} speed and RAM ===", file=sys.stderr, flush=True)
            command = [str(args.casanovo), "sequence", "--config", str(config),
                       "--model", str(args.model), "--force_overwrite", "--output_dir", str(out),
                       "--output_root", "result", str(args.input)]
            result = benchmark.measure(args.casanovo, config, args.input, out,
                                       device, context["CPU"], command=command)
            results.append(result)
            count, _ = psm_fingerprint(out / "result.mztab")
            if result.exit_code != 0 or result.predict_seconds is None or count == 0 or count != result.spectra:
                status = 1
    missing_memory = (
        any(r.peak_host_gb is None or (r.device == "gpu" and r.peak_gpu_mib is None)
            for r in results)
        or any(r["peak_host_ram_gib"] is None or r["peak_gpu_mib"] is None
               for r in memory_results))
    if missing_memory:
        status = 1
        print("Memory readings are unavailable; results were saved but the document will not be updated.",
              file=sys.stderr)
    speed_report = benchmark.render(results, context)
    memory_report = render(data["context"], memory_results)
    report = speed_report + "\n\n" + memory_report
    (args.out_dir / "benchmark.md").write_text(report, encoding="utf-8")
    write_json(args.out_dir / "benchmark.json", {
        "context": data["context"], "results": [asdict(r) for r in results],
        "gpu_memory_results": memory_results})
    if status == 0 and args.update_doc:
        text = args.update_doc.read_text(encoding="utf-8")
        text = replace_doc_block(text, speed_report, "<!-- BENCHMARK:BEGIN -->", "<!-- BENCHMARK:END -->")
        text = replace_doc_block(text, memory_report)
        args.update_doc.write_text(text, encoding="utf-8")
    print(report)
    return status


if __name__ == "__main__":
    worker(Path(sys.argv[1]))
