"""Assert that a CPU run never reaches for an accelerator.

This is the regression behind the "Cannot access accelerator device when none is available."
report: PyTorch raises that from `at::accelerator::getAccelerator(checked=true)` whenever the
installed wheel has no accelerator backend compiled in. Casanovo builds its inference
DataLoader with `pin_memory=True` regardless of the selected accelerator, which is the path
that would otherwise ask PyTorch for an accelerator device index.

Run by .github/workflows/smoke.yml on each platform's freshly installed Casanovo environment.
Must be a real file rather than `python -` : on Windows, `multiprocessing` spawn re-imports
__main__ from its path, which fails for stdin. The __main__ guard below is required for the
same reason.
"""

import sys
import warnings

import torch
from torch.utils.data import DataLoader, TensorDataset


def main() -> int:
    print("torch", torch.__version__, "| cuda build:", torch.version.cuda)

    dataset = TensorDataset(torch.arange(8).float().view(8, 1))
    for workers in (0, 2):
        with warnings.catch_warnings():
            # A CPU-only wheel warns "no accelerator is found"; that is the correct,
            # non-fatal behaviour and not what this check is looking for.
            warnings.simplefilter("ignore")
            loader = DataLoader(dataset, batch_size=2, pin_memory=True, num_workers=workers)
            batches = sum(1 for _ in loader)
        print("pin_memory DataLoader ok: num_workers=%d, %d batches" % (workers, batches))

    # The guarded accessors must answer rather than raise. Only the checked accessor
    # (current_device_index) may raise on such a build, and nothing in Casanovo's dependency
    # tree calls it -- this asserts that the guarded ones stay safe.
    if hasattr(torch, "accelerator"):
        print("accelerator.is_available:", torch.accelerator.is_available())
        print("accelerator.current_accelerator:", torch.accelerator.current_accelerator())

    print("OK: a CPU run touched no accelerator")
    return 0


if __name__ == "__main__":
    sys.exit(main())
