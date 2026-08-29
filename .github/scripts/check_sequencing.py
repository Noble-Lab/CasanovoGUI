#!/usr/bin/env python3
"""Check that a Casanovo run on the example MGF reproduces the expected peptides.

Turns the smoke test from "it didn't crash" into a real regression check: a broken install --
wrong model weights, a mis-resolved PyTorch, a half-loaded checkpoint -- still exits zero and
still writes an mzTab, but the peptides it writes are wrong.

Join key
--------
`mgf_index`, not the scan number. Sequencing an MGF makes Casanovo report `spectra_ref` as
`ms_run[1]:index=N`, the 0-based position in the file; the original scan number is not carried
through.

Why a tolerance rather than an exact match
------------------------------------------
The expected peptides were produced on one particular machine (CUDA, one PyTorch build). CI
runs on the CPU with whatever PyTorch the platform resolved, and floating-point differences
can occasionally flip a single residue. Demanding 50/50 would make the job flaky for a reason
that is not a real defect, while a broken install produces nothing like the expected output --
so the threshold is deliberately loose and every mismatch is printed.

Usage:
    python check_sequencing.py <result.mztab> <expected.tsv> [--min-agreement 0.9]
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path


def read_expected(path: Path) -> dict[int, str]:
    with path.open(encoding="utf-8") as handle:
        return {int(row["mgf_index"]): row["peptide"]
                for row in csv.DictReader(handle, delimiter="\t")}


def read_mztab(path: Path) -> dict[int, str]:
    """Map each PSM's 0-based MGF index to the peptide Casanovo called."""
    peptides: dict[int, str] = {}
    columns: dict[str, int] | None = None

    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("PSH\t"):
                columns = {name.strip(): i
                           for i, name in enumerate(line.rstrip("\n").split("\t"))}
                continue
            if columns is None or not line.startswith("PSM\t"):
                continue
            cells = line.rstrip("\n").split("\t")
            ref = cells[columns["spectra_ref"]]
            if "index=" not in ref:
                continue
            peptides[int(ref.rsplit("index=", 1)[1])] = cells[columns["sequence"]]

    if columns is None:
        sys.exit(f"No PSM section (PSH header) found in {path.name}")
    return peptides


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mztab", type=Path, help="the mzTab Casanovo just wrote")
    parser.add_argument("expected", type=Path, help="the fixture's .expected.tsv manifest")
    parser.add_argument("--min-agreement", type=float, default=0.9,
                        help="fraction of peptides that must match exactly (default: 0.9)")
    args = parser.parse_args()

    if not args.mztab.is_file():
        sys.exit(f"Casanovo produced no mzTab at {args.mztab}")

    expected = read_expected(args.expected)
    observed = read_mztab(args.mztab)
    print(f"expected {len(expected)} spectra, sequenced {len(observed)}")

    if len(observed) != len(expected):
        missing = sorted(set(expected) - set(observed))
        print(f"::error::Sequenced {len(observed)} of {len(expected)} spectra; "
              f"missing indices {missing[:10]}")
        return 1

    agreed = [i for i in expected if observed.get(i) == expected[i]]
    for index in sorted(set(expected) - set(agreed)):
        print(f"  differs at index {index}: expected {expected[index]!r}, "
              f"got {observed.get(index)!r}")

    if not expected:
        print("::error::the reference file lists no peptides")
        return 1
    fraction = len(agreed) / len(expected)
    print(f"identical peptides: {len(agreed)}/{len(expected)} ({fraction:.0%})")

    if fraction < args.min_agreement:
        print(f"::error::Only {fraction:.0%} of peptides matched, below the "
              f"{args.min_agreement:.0%} threshold. This installation does not reproduce the "
              f"reference result -- check the model weights and the resolved PyTorch build.")
        return 1

    print("OK: this installation reproduces the reference result")
    return 0


if __name__ == "__main__":
    sys.exit(main())
