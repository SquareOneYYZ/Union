#!/bin/bash
# DC600 Test Script Runner for Linux/Mac
#
# Usage:
#   ./run_test.sh                      - Test against localhost:5049
#   ./run_test.sh 192.168.1.100        - Test against specific host
#   ./run_test.sh 192.168.1.100 5049   - Test against specific host:port

echo "================================================================"
echo "DC600 Event and Multimedia Upload Test"
echo "================================================================"
echo

# Check if Python is installed
if ! command -v python3 &> /dev/null; then
    echo "ERROR: Python 3 is not installed or not in PATH"
    echo "Please install Python 3.6 or higher"
    exit 1
fi

# Set default values
HOST=${1:-localhost}
PORT=${2:-5049}
DEVICE_ID=${3:-123456789012345}

echo "Target Server: $HOST:$PORT"
echo "Device ID: $DEVICE_ID"
echo
echo "Starting test..."
echo

# Run the test
python3 test_event_multimedia.py "$HOST" "$PORT" "$DEVICE_ID"

if [ $? -eq 0 ]; then
    echo
    echo "TEST COMPLETED!"
    exit 0
else
    echo
    echo "TEST FAILED!"
    exit 1
fi
