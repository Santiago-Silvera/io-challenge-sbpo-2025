import os
import subprocess
import sys
import platform
import psutil

# Paths to the libraries
CPLEX_PATH = os.path.expandvars("$HOME/CPLEX_Studio2211/opl/bin/arm64_osx/")
OR_TOOLS_PATH = os.path.expandvars("$HOME/Documents/or-tools/build/lib/")

# Feature toggles
USE_CPLEX = True
USE_OR_TOOLS = True
USE_TIMEOUT = False  # Set to False to disable timeout, True to enable timeout

# Timeout duration (only used if USE_TIMEOUT is True)
MAX_RUNNING_TIME = "605s"

class TimeoutError(Exception):
    """Custom exception for timeout errors."""
    pass


def kill_existing_java_instances():
    """Kill any running Java processes using the challenge JAR."""
    for proc in psutil.process_iter(['pid', 'cmdline']):
        cmdline = proc.info.get('cmdline')
        if cmdline and 'ChallengeSBPO2025-1.0.jar' in ' '.join(cmdline):
            print(f"Killing stale Java process: PID {proc.pid}")
            try:
                proc.kill()
            except Exception as e:
                print(f"Failed to kill process {proc.pid}: {e}")


def compile_code(source_folder):
    print(f"Compiling code in {source_folder}...")

    result = subprocess.run(
        ["mvn", "clean", "package"],
        capture_output=True,
        text=True,
        cwd=source_folder
    )

    if result.returncode != 0:
        print("Maven compilation failed:")
        print(result.stderr)
        return False

    print("Maven compilation successful.")
    return True


def run_benchmark(source_folder, input_folder, output_folder):
    # Kill stale Java processes before launching new ones
    kill_existing_java_instances()

    # Ensure output folder exists
    os.makedirs(output_folder, exist_ok=True)

    # Determine combined library path
    if USE_CPLEX and USE_OR_TOOLS:
        libraries = f"{OR_TOOLS_PATH}:{CPLEX_PATH}"
    elif USE_CPLEX:
        libraries = CPLEX_PATH
    elif USE_OR_TOOLS:
        libraries = OR_TOOLS_PATH
    else:
        libraries = None

    # Prepare timeout prefix
    prefix = []
    if USE_TIMEOUT:
        timeout_cmd = "gtimeout" if platform.system() == "Darwin" else "timeout"
        prefix = [timeout_cmd, MAX_RUNNING_TIME]

    # Get the path to the JAR file
    jar_path = os.path.join(source_folder, "target", "ChallengeSBPO2025-1.0.jar")
    print(f"Using JAR: {jar_path}")

    for filename in os.listdir(input_folder):
        if not filename.endswith(".txt"): continue

        print(f"Running {filename}")
        input_file = os.path.join(input_folder, filename)
        output_file = os.path.join(output_folder, f"{os.path.splitext(filename)[0]}.txt")

        # Build Java invocation
        java_cmd = ["java"]
        if libraries:
            java_cmd.append(f"-Djava.library.path={libraries}")
        java_cmd += [
            "--enable-native-access=ALL-UNNAMED",
            "-Xmx16g",
            "-jar",
            jar_path,
            input_file,
            output_file
        ]

        cmd = prefix + java_cmd

        result = subprocess.run(
            cmd,
            stderr=subprocess.PIPE,
            text=True,
            cwd=source_folder
        )

        # Handle timeout
        if USE_TIMEOUT and result.returncode == 124:
            error_msg = f"Execution timed out after {MAX_RUNNING_TIME} for {input_file}"
            print(error_msg)
            raise TimeoutError(error_msg)
        # Handle other runtime errors
        if result.returncode != 0:
            print(f"Execution failed for {input_file}:")
            print(result.stderr)
            raise RuntimeError(f"Execution failed for {input_file}: {result.stderr}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Usage: python run_challenge.py <source_folder> <input_folder> <output_folder>")
        sys.exit(1)

    source_folder = os.path.abspath(sys.argv[1])
    input_folder = os.path.abspath(sys.argv[2])
    output_folder = os.path.abspath(sys.argv[3])

    # Compile and then run benchmark
    if compile_code(source_folder):
        run_benchmark(source_folder, input_folder, output_folder)

