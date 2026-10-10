pipeline {
    agent any
    options { timestamps(); timeout(time: 60, unit: 'MINUTES') }

    environment {
        KERNEL_REPO = 'https://github.com/Arkenys/android_kernel_samsung_sm8250.git'
        KERNEL_BRANCH = 'lineage-23.2'
        TOOLCHAIN_REPO = 'https://github.com/kdrag0n/proton-clang.git'
    }

    stages {
        stage('Clean Old Artifacts') {
            steps {
                // Delete previous build outputs, but LEAVE the heavy kernel/ and toolchain/ folders!
                sh "rm -rf out AnyKernel3 *.zip .docker-env"
            }
        }

        stage('Prepare Build Environment') {
            steps {
                // ISOLATE the Docker build context in an empty folder so it doesn't scan the 3GB kernel
                dir('.docker-env') {
                    script {
                        echo "=== Verifying Docker Image Cache ==="
                        writeFile file: 'Dockerfile.ci', text: '''\
                            FROM debian:bookworm-slim
                            ENV DEBIAN_FRONTEND=noninteractive
                            RUN apt-get update -qq && apt-get install -qq -y --no-install-recommends \\
                                git ccache automake flex lzop bison gperf build-essential zip curl \\
                                zlib1g-dev libssl-dev bc unzip python3 ca-certificates \\
                                gcc-aarch64-linux-gnu gcc-arm-linux-gnueabi cpio tar xz-utils > /dev/null 2>&1
                        '''.stripIndent()

                        env.BUILDER_IMAGE = docker.build("android-kernel-builder:latest", "-q -f Dockerfile.ci .").id
                    }
                }
            }
        }

        stage('Checkout (Incremental)') {
            steps {
                echo "=== Syncing Toolchain ==="
                dir('toolchain') {
                    checkout([
                        $class: 'GitSCM', 
                        branches: [[name: '*/master']], 
                        doGenerateSubmoduleConfigurations: false, 
                        // CleanBeforeCheckout wipes old files, but CloneOption keeps it shallow!
                        extensions: [
                            [$class: 'CleanBeforeCheckout'],
                            [$class: 'CloneOption', depth: 1, noTags: true, reference: '', shallow: true]
                        ], 
                        userRemoteConfigs: [[url: env.TOOLCHAIN_REPO]]
                    ])
                }

                echo "=== Syncing Kernel ==="
                dir('kernel') {
                    checkout([
                        $class: 'GitSCM', 
                        branches: [[name: "*/${env.KERNEL_BRANCH}"]], 
                        doGenerateSubmoduleConfigurations: false, 
                        extensions: [
                            [$class: 'CleanBeforeCheckout'],
                            [$class: 'CloneOption', depth: 1, noTags: true, reference: '', shallow: true]
                        ], 
                        userRemoteConfigs: [[url: env.KERNEL_REPO]]
                    ])
                }
            }
        }

        stage('Compile & Package') {
            steps {
                script {
                    docker.image(env.BUILDER_IMAGE).inside {
                        stage('Compile') {
                            echo "=== Compiling Patched Kernel ==="
                            sh """#!/bin/bash
                                set +x
                                export PATH="\$PATH:\$WORKSPACE/toolchain/bin"
                                cd kernel

                                BASE_CONF="vendor/kona-perf_defconfig"
                                FRAG_COMMON=\$(cd arch/arm64/configs && find vendor/samsung -type f -name "kona-sec-common*" | head -n 1)
                                FRAG_DEVICE=\$(cd arch/arm64/configs && find vendor/samsung -type f -name "*gts7*wifi*" | head -n 1)
                                
                                make -s O=../out ARCH=arm64 CC=clang CROSS_COMPILE=aarch64-linux-gnu- CROSS_COMPILE_ARM32=arm-linux-gnueabi- \$BASE_CONF
                                ARCH=arm64 scripts/kconfig/merge_config.sh -m -O ../out ../out/.config arch/arm64/configs/\$FRAG_COMMON arch/arm64/configs/\$FRAG_DEVICE > /dev/null 2>&1
                                make -s O=../out ARCH=arm64 CC=clang CROSS_COMPILE=aarch64-linux-gnu- CROSS_COMPILE_ARM32=arm-linux-gnueabi- olddefconfig
                                
                                echo "🚀 Compiling Kernel..."
                                make -s -j\$(nproc) O=../out ARCH=arm64 CC=clang CROSS_COMPILE=aarch64-linux-gnu- CROSS_COMPILE_ARM32=arm-linux-gnueabi- KCFLAGS="-Wno-error -Wno-implicit-function-declaration"
                            """
                        }

                        stage('Package with AnyKernel3') {
                            echo "=== Packaging Flashable Zip ==="
                            sh """#!/bin/bash
                                set +x
                                SHORT_HASH=\$(cd kernel && git rev-parse --short HEAD)
                                
                                git clone --quiet --depth=1 https://github.com/osm0sis/AnyKernel3.git AnyKernel3
                                cd AnyKernel3
                                
                                sed -i 's/kernel.string=.*/kernel.string=Arkenys Custom Kernel/' anykernel.sh
                                sed -i 's/device.name1=.*/device.name1=gts7lwifi/' anykernel.sh
                                sed -i 's/device.name2=.*/device.name2=gts7l/' anykernel.sh
                                sed -i 's/device.name3=.*/device.name3=/' anykernel.sh
                                sed -i 's/device.name4=.*/device.name4=/' anykernel.sh
                                sed -i 's/device.name5=.*/device.name5=/' anykernel.sh
                                
                                # FIX: Tell AnyKernel exactly where the Snapdragon boot partition is
                                sed -i 's|/dev/block/platform/omap/omap_hsmmc.0/by-name/boot|/dev/block/bootdevice/by-name/boot|g' anykernel.sh
                                
                                cp ../out/arch/arm64/boot/Image ./Image
                                
                                ZIP_NAME="TabS7-Kernel-\${SHORT_HASH}.zip"
                                zip -q -r9 ../\${ZIP_NAME} * -x .git README.md *placeholder
                            """
                        }
                    }
                }
            }
        }
        
        stage('Archive Artifacts') {
            steps {
                archiveArtifacts artifacts: "TabS7-Kernel-*.zip", fingerprint: true
            }
        }
    }
}

