# Report what the installed PyTorch can actually use, as MARKER-prefixed key=value lines.
#
# Read by DeviceProbe.java before every run to check the selected accelerator, and by the
# CI smoke test to assert what each platform resolved to. Kept as a resource rather than a
# string constant so both use the identical script.
#
# Every lookup is guarded individually: an older PyTorch missing one of these attributes must
# degrade to "unknown" rather than fail the whole probe.


# Every field line carries this marker, and DeviceProbe reads no line without it. stdout and
# stderr are merged and the interpreter is not always launched directly — `conda run` shares the
# stream with the environment's own banners and warnings — so a bare "key=value" line here is not
# necessarily ours. Unmarked, an unrelated "error=..." failed a healthy probe and an unrelated
# "cuda_available=true" could have invented a device. Keep in step with DeviceProbe.MARKER and
# with the CI smoke test, which parses this same output.
MARKER = "casanovo-probe:"


def emit(key, value):
    # flush=True because DeviceProbe redirects this to a file, not a terminal: Python would
    # otherwise block-buffer, and the crash this probe exists to diagnose (the Windows MKL/OpenMP
    # access violation during a torch import) would take every line with it.
    print("%s%s=%s" % (MARKER, key, value), flush=True)


try:
    import torch
except Exception as exc:
    emit("error", "cannot import torch: %s" % exc)
    raise SystemExit(0)

emit("torch", getattr(torch, "__version__", "?"))
emit("cuda_build", getattr(getattr(torch, "version", None), "cuda", None) or "")

cuda_available = False
try:
    cuda_available = bool(torch.cuda.is_available())
except Exception:
    pass
emit("cuda_available", cuda_available)

if cuda_available:
    try:
        emit("cuda_name", torch.cuda.get_device_name(0))
    except Exception:
        pass
    try:
        major, minor = torch.cuda.get_device_capability(0)
        emit("cuda_arch", "sm_%d%d" % (major, minor))
    except Exception:
        pass
    try:
        # Total VRAM in bytes. The environment report prints it because "out of memory" is the
        # other way a run dies on a perfectly compatible GPU, and the card's name does not say
        # which variant (an 8 GB or a 16 GB board of the same model) the user has.
        emit("cuda_mem", torch.cuda.get_device_properties(0).total_memory)
    except Exception:
        pass
try:
    emit("arch_list", ";".join(torch.cuda.get_arch_list()))
except Exception:
    pass

try:
    emit("mps_built", bool(torch.backends.mps.is_built()))
except Exception:
    emit("mps_built", False)
try:
    emit("mps_available", bool(torch.backends.mps.is_available()))
except Exception:
    emit("mps_available", False)
