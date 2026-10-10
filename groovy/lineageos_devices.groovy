pipeline {
    agent {
        docker { 
            image 'python:3.11' 
            reuseNode true 
        }
    }
    
    triggers {
        cron('H 8 * * *')
    }

    stages {
        stage('Setup Git History') {
            steps {
                // Clone or update the LineageOS hudson repository locally
                sh '''
                    if [ -d "hudson" ]; then
                        cd hudson && git pull
                    else
                        git clone https://github.com/LineageOS/hudson.git
                    fi
                '''
            }
        }
        
        stage('Create Script') {
            steps {
                writeFile file: 'generate_feed.py', text: '''
import json
import os
import subprocess
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

STATE_FILE = "state.json"
RSS_FILE = "lineageos_devices.xml"
HUDSON_DIR = "hudson"
DEVICES_FILE = os.path.join(HUDSON_DIR, "updater", "devices.json")
TARGETS_FILE = os.path.join(HUDSON_DIR, "lineage-build-targets")

def get_active_codenames():
    """Parses the local lineage-build-targets file for active devices."""
    active = set()
    if not os.path.exists(TARGETS_FILE):
        return active
    with open(TARGETS_FILE, "r") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                active.add(line.split()[0])
    return active

def get_git_addition_date(model_codename):
    """Queries git log to find the true historical addition date."""
    try:
        regex_string = f'"model"\\s*:\\s*"{model_codename}"'
        cmd = [
            "git", "-C", HUDSON_DIR, "log", 
            "-G", regex_string, 
            "--format=%aI", 
            "--reverse", 
            "--", "updater/devices.json"
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, check=True)
        dates = result.stdout.strip().splitlines()
        if dates and dates[0]:
            return dates[0] 
    except Exception:
        pass
    return datetime.now(timezone.utc).isoformat()

def main():
    if not os.path.exists(DEVICES_FILE):
        print("Error: LineageOS devices file missing.")
        return

    active_codenames = get_active_codenames()
    with open(DEVICES_FILE, "r") as f:
        all_devices = json.load(f)

    # Filter: ONLY keep devices that are currently actively supported
    active_devices = [d for d in all_devices if d.get("model") in active_codenames]

    if os.path.exists(STATE_FILE):
        with open(STATE_FILE, "r") as f:
            state = json.load(f)
    else:
        state = {}

    # Look up dates for active devices we haven't cached yet
    for device in active_devices:
        model = device.get("model")
        if model not in state:
            print(f"Mining history for active device: {model}")
            state[model] = {
                "date_added": get_git_addition_date(model),
                "name": device.get("name", "Unknown"),
                "oem": device.get("oem", "Unknown")
            }

    # Clean state: Remove old cached items that are no longer in the active list
    # This keeps our history strictly tied to currently supported hardware
    for model in list(state.keys()):
        if model not in active_codenames:
            del state[model]

    with open(STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)

    # Sort all active hardware by their true historical addition date, newest first
    # We remove the limit entirely so the feed contains the complete active database history
    sorted_devices = sorted(state.items(), key=lambda x: x[1]["date_added"], reverse=True)

    # Build the RSS Feed
    rss = ET.Element("rss", version="2.0")
    channel = ET.SubElement(rss, "channel")
    ET.SubElement(channel, "title").text = "LineageOS Supported Devices History"
    ET.SubElement(channel, "link").text = "https://wiki.lineageos.org/devices/"
    ET.SubElement(channel, "description").text = "Historical timeline of all currently supported LineageOS devices."

    for model, info in sorted_devices:
        item = ET.SubElement(channel, "item")
        
        ET.SubElement(item, "title").text = f"🟢 Supported: {info['oem']} {info['name']} ({model})"
        ET.SubElement(item, "link").text = f"https://wiki.lineageos.org/devices/{model}"
        ET.SubElement(item, "guid", isPermaLink="false").text = f"{model}-active"
        
        try:
            dt = datetime.fromisoformat(info["date_added"])
        except ValueError:
            dt = datetime.now(timezone.utc)
            
        ET.SubElement(item, "pubDate").text = dt.strftime("%a, %d %b %Y %H:%M:%S +0000")
        ET.SubElement(item, "description").text = f"The {info['oem']} {info['name']} ({model}) is an actively supported LineageOS device, originally added on {dt.strftime('%Y-%m-%d')}."

    tree = ET.ElementTree(rss)
    tree.write(RSS_FILE, encoding="utf-8", xml_declaration=True)
    print(f"Successfully generated history feed with {len(sorted_devices)} active entries.")

if __name__ == "__main__":
    main()
'''
            }
        }
        
        stage('Generate Feed') {
            steps {
                sh 'python generate_feed.py'
            }
        }
    }
    
    post {
        always {
            archiveArtifacts artifacts: 'lineageos_devices.xml', allowEmptyArchive: false
        }
    }
}

