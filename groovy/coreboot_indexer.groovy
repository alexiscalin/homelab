import jenkins.model.*
import hudson.model.*
import java.io.File

pipeline {
    agent any
    stages {
        stage('1. Download Coreboot') {
            steps {
                checkout([
                    $class: 'GitSCM', 
                    branches: [[name: '*/main']], 
                    doGenerateSubmoduleConfigurations: false, 
                    extensions: [[$class: 'SubmoduleOption', disableSubmodules: true]], 
                    userRemoteConfigs: [[url: 'https://review.coreboot.org/coreboot.git']]
                ])
            }
        }
        
        stage('2. Generate List') {
            steps {
                script {
                    def workspace = env.WORKSPACE
                    // On utilise Bash pour scanner l'arborescence instantanément
                    sh '''#!/bin/bash
                    rm -f coreboot_boards.txt
                    for vendor_dir in src/mainboard/*; do
                        [ -d "$vendor_dir" ] || continue
                        vendor=$(basename "$vendor_dir")
                        
                        board_list=""
                        for board_dir in "$vendor_dir"/*; do
                            [ -d "$board_dir" ] || continue
                            baseboard=$(basename "$board_dir")
                            
                            if [ -d "$board_dir/variants" ]; then
                                for var_dir in "$board_dir/variants"/*; do
                                    [ -d "$var_dir" ] || continue
                                    variant=$(basename "$var_dir")
                                    # Format: NomAffiché=CheminRelatif
                                    board_list="$board_list$variant [$baseboard]=$baseboard/variants/$variant,"
                                done
                            else
                                board_list="$board_list$baseboard=$baseboard,"
                            fi
                        done
                        
                        if [ -n "$board_list" ]; then
                            echo "$vendor:${board_list%,}" >> coreboot_boards.txt
                        fi
                    done
                    '''
                    // Copie sur le master Jenkins
                    def masterDb = new File(Jenkins.instance.rootDir, "coreboot_boards.txt")
                    masterDb.text = readFile('coreboot_boards.txt')
                    echo "✅ Base de données générée proprement !"
                }
            }
        }
    }
}

