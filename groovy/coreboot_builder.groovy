// =============================================================================
//  Coreboot Builder  (production pipeline)
// -----------------------------------------------------------------------------
//  Parameters (TARGET_NODE, VENDOR, TARGET_BOARD, CONFIG_PAYLOAD, stock.rom)
//  live in the JOB CONFIGURATION (Active Choices / uno-choice plugin).
//  Do NOT add a `parameters {}` block here: a declarative parameters block
//  overwrites the job's parameter definitions on every run and wipes the
//  cascading VENDOR/TARGET_BOARD dropdowns.
//
//  Safety model (prevents brick-ROMs):
//   * Builds from a PINNED coreboot release tag, never main.
//   * Boards with Intel Boot Guard are deguarded automatically (patched ME +
//     HAP bit). A Boot Guard board that cannot be deguarded fails the build
//     instead of emitting an unbootable image.
//   * The built ROM is size-checked against CONFIG_ROM_SIZE before deploy.
//   * ROMs are deployed PER BOARD with a manifest (model, DMI match, sha256,
//     size, whether a flash descriptor is present). The PXE flasher uses that
//     manifest to refuse flashing the wrong board or a corrupt image.
// =============================================================================

import groovy.transform.Field

// --- Pinned coreboot release. Bump deliberately; never track main. ---
@Field final String CB_VERSION = '26.09'

// --- Boards that require deguard (Intel Boot Guard) AND are supported by the
//     upstream deguard utility. Keyed by coreboot variant model name.
//     Adding a board here = that board is treated as Boot-Guard and deguarded.
@Field final Map DEGUARD_TARGETS = [
    't470s': [delta: 'thinkpad_t470s', pch: 'LP', sku: '2M', version: '11.6.0.1126'],
    't480' : [delta: 'thinkpad_t480',  pch: 'LP', sku: '2M', version: '11.6.0.1126'],
    't480s': [delta: 'thinkpad_t480s', pch: 'LP', sku: '2M', version: '11.6.0.1126'],
    't580' : [delta: 'thinkpad_t580',  pch: 'LP', sku: '2M', version: '11.6.0.1126'],
    'x280' : [delta: 'thinkpad_x280',  pch: 'LP', sku: '2M', version: '11.6.0.1126'],
]

// --- Baseboards whose every variant has Intel Boot Guard. A variant of one of
//     these that is NOT in DEGUARD_TARGETS (e.g. x380_yoga, which has no
//     upstream delta) will fail the build rather than ship an unbootable ROM.
@Field final List BOOTGUARD_BASEBOARDS = ['sklkbl_thinkpad']

// --- DMI string identifying each board on live hardware. The flasher compares
//     it against BOTH /sys/class/dmi/id/product_version (Lenovo puts the model
//     there) and product_name (HP/Dell put it there). Verify on the machine:
//         cat /sys/class/dmi/id/product_version /sys/class/dmi/id/product_name
//     A board without an entry still builds, but the flasher will never match
//     it (it refuses rather than guessing).
@Field final Map DMI_MATCH = [
    't470s': 'ThinkPad T470s', 't480': 'ThinkPad T480', 't480s': 'ThinkPad T480s',
    't580' : 'ThinkPad T580',  'x280': 'ThinkPad X280',
    // pre-Boot-Guard boards you also flash:
    't430' : 'ThinkPad T430',  'x230': 'ThinkPad X230',
    't420' : 'ThinkPad T420',  'x220': 'ThinkPad X220',
    'prodesk_600_g1_sff': 'HP ProDesk 600 G1 SFF',
]

// --- deguard donor ME (LP / 2M / 11.6.0.1126). See coreboot deguard.md. ---
@Field final String DEGUARD_REPO     = 'https://review.coreboot.org/deguard'
@Field final String DONOR_LP_URL     = 'https://web.archive.org/web/20241110222323/https://dl.dell.com/FOLDER04573471M/1/Inspiron_5468_1.3.0.exe'
@Field final String DONOR_LP_SHA256  = '912271bb3ff2cf0e2e27ccfb94337baaca027e6c90b4245f9807a592c8a652e1'
@Field final int    DONOR_LP_SIZE    = 0x1f0000   // 2031616 bytes
@Field final String DELL_PFS_EXTRACT = 'https://raw.githubusercontent.com/vuquangtrong/Dell-PFS-BIOS-Assembler/master/Dell_PFS_Extract.py'

// Host dir (bind-mounted into jenkins_server) served over HTTP by the PXE box.
// Must be writable by the Jenkins uid -- the PXE Ansible role sets that up.
@Field final String TFTP_COREBOOT = '/srv/tftp/coreboot'

pipeline {
    agent { label "${params.TARGET_NODE.trim()}" }
    // skipDefaultCheckout: this job's Jenkinsfile comes from the homelab repo,
    // but the workspace must hold coreboot -- don't clone homelab into it.
    options { skipDefaultCheckout(); timestamps(); timeout(time: 90, unit: 'MINUTES') }

    stages {
        stage('Clean Workspace') {
            steps {
                echo "=== Running on node: ${params.TARGET_NODE.trim()} ==="
                echo "=== Smart Clean: nuke build output, keep EDK2 tools + deguard cache ==="
                script {
                    docker.image('alpine:latest').inside("-u 0:0") {
                        sh "rm -rf build .config .docker-env build*.log flashregion_*.bin stock.rom"
                        sh "rm -rf payloads/external/edk2/workspace/Build"
                        // .deguard_cache (deguard clone + donor ME) is kept on purpose.
                    }
                }
            }
        }

        stage('Checkout') {
            steps {
                echo "=== Checkout coreboot ${CB_VERSION} (pinned release tag) ==="
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "refs/tags/${CB_VERSION}"]],
                    extensions: [
                        [$class: 'CloneOption', shallow: true, depth: 1, noTags: false],
                        [$class: 'SubmoduleOption', disableSubmodules: false, recursiveSubmodules: true]
                    ],
                    userRemoteConfigs: [[
                        url: 'https://review.coreboot.org/coreboot.git',
                        refspec: "+refs/tags/${CB_VERSION}:refs/tags/${CB_VERSION}"
                    ]]
                ])
                sh 'git submodule update --init --checkout --recursive --quiet 3rdparty/blobs 3rdparty/intel-microcode 3rdparty/fsp'
                script {
                    def desc = sh(script: 'git describe --tags --always', returnStdout: true).trim()
                    echo "✅ coreboot checked out at: ${desc}"
                }
            }
        }

        stage('Build') {
            steps {
                script {
                    def workspace = env.WORKSPACE

                    // stock.rom is delivered by a "Stashed File Parameter"
                    // (file-parameters plugin). Unstash it into the workspace.
                    // Absent or empty means "no stock dump" -> no blob injection.
                    // This replaces the old rawBuild/FileParameterValue reflection
                    // so the job runs under the Groovy sandbox with no
                    // In-Process Script Approval (works as Pipeline-from-SCM).
                    def romReady = false
                    try {
                        unstash 'stock.rom'
                        romReady = (sh(script: "[ -s stock.rom ]", returnStatus: true) == 0)
                        if (romReady) {
                            echo "✅ stock.rom provided (" + sh(script: "stat -c%s stock.rom", returnStdout: true).trim() + " bytes)."
                        }
                    } catch (ignored) {
                        echo "ℹ️  No stock.rom received. If you DID upload one, the 'stock.rom' job parameter " +
                             "is still a classic File Parameter: it must be a 'Stashed File Parameter' " +
                             "(file-parameters plugin) named exactly 'stock.rom', or the upload is invisible to the pipeline."
                    }

                    def vendor = params.VENDOR
                    def selection = params.TARGET_BOARD
                    if (vendor == "Run_Indexer_First" || selection == "Select a Vendor first") {
                        error "Please select a valid vendor and board."
                    }

                    // "t480s [sklkbl_thinkpad]" -> model=t480s, baseboard=sklkbl_thinkpad
                    def model = selection.split(' ')[0]
                    def baseboard = selection.contains('[') ? selection.split('\\[')[1].replaceAll('\\]', '') : model
                    def boardPath = (model != baseboard) ? "${baseboard}/variants/${model}" : baseboard

                    def deguardSpec = DEGUARD_TARGETS[model]
                    def needsBootGuard = (baseboard in BOOTGUARD_BASEBOARDS) || (deguardSpec != null)

                    echo "Target: ${vendor} - Baseboard: ${baseboard} - Model: ${model}"
                    if (!DMI_MATCH.containsKey(model)) {
                        echo "⚠️  (warning, not an error) No DMI string for '${model}' in DMI_MATCH: the ROM will be " +
                             "archived and published, but the PXE flasher will refuse to flash it until you add one."
                    }

                    // Fail fast: a Boot Guard board we cannot deguard must not ship.
                    if (needsBootGuard && deguardSpec == null) {
                        error "❌ ${model} has Intel Boot Guard but upstream deguard has no delta for it. " +
                              "Refusing to build an unbootable ROM. Generate/submit deguard delta data or pick a supported variant."
                    }
                    if (deguardSpec != null) {
                        echo "⚠️  ${model} has Intel Boot Guard -> deguard will run (delta=${deguardSpec.delta}, pch=${deguardSpec.pch})."
                        if (deguardSpec.pch != 'LP') {
                            error "❌ deguard donor for PCH '${deguardSpec.pch}' is not wired up in this pipeline (only LP/laptop)."
                        }
                    }

                    def hasIfd = false

                    docker.image('coreboot/coreboot-sdk:latest').inside("-e CCACHE_DIR=${workspace}/.ccache") {
                        sh "mkdir -p .ccache"

                        def vendorDir = "src/mainboard/${vendor}/${boardPath}"
                        if (sh(script: "[ -d '${vendorDir}' ]", returnStatus: true) != 0) {
                            error "Board dir missing: ${vendorDir}"
                        }

                        def boardSymbol = sh(script: """
                            SYM=\$(grep -h "config BOARD_" "${vendorDir}/Kconfig.name" 2>/dev/null | awk '{print \$2}' | head -n 1)
                            if [ -z "\$SYM" ]; then
                                SYM=\$(grep -i "config BOARD_.*_${model}\$" "${vendorDir}/../../Kconfig.name" 2>/dev/null | awk '{print \$2}' | head -n 1)
                            fi
                            echo "\$SYM"
                        """, returnStdout: true).trim()
                        if (!boardSymbol) error "❌ Impossible de trouver le symbole Kconfig pour ${model}!"
                        echo "✅ Symbole Kconfig : ${boardSymbol}"

                        def netPciIds = ['x220': '8086,1502', 'x230': '8086,1502', 't420': '8086,1502', 't480s': '8086,15d8', 'default': '10ec,8168']
                        def pciId = netPciIds.get(model, netPciIds['default'])

                        def kconfigLines = [
                            "CONFIG_VENDOR_${vendor.toUpperCase()}=y",
                            "CONFIG_${boardSymbol}=y",
                            "CONFIG_PAYLOAD_${params.CONFIG_PAYLOAD}=y",
                            "CONFIG_CCACHE=y"
                        ]

                        // ifdtool is needed for both paths.
                        if (sh(script: "[ ! -f util/ifdtool/ifdtool ]", returnStatus: true) == 0) {
                            sh "make -s -C util/ifdtool > /dev/null 2>&1"
                        }

                        // --- BLOB INJECTION ---
                        if (deguardSpec != null) {
                            if (!(romReady && sh(script: "[ -s stock.rom ]", returnStatus: true) == 0)) {
                                error "❌ ${model} needs a stock.rom dump (for IFD + GBE) to run deguard. Upload the vendor flash dump via the stock.rom parameter."
                            }
                            kconfigLines += prepareDeguardBlobs(vendor, model, deguardSpec)
                            hasIfd = true
                        } else if (romReady && sh(script: "[ -s stock.rom ]", returnStatus: true) == 0) {
                            echo "=== Injecting Blobs (generic, no Boot Guard) ==="
                            sh "./util/ifdtool/ifdtool -x stock.rom > /dev/null 2>&1"
                            def blobDest = "3rdparty/blobs/mainboard/${vendor}/${model}"
                            sh "mkdir -p ${blobDest}"
                            sh "mv flashregion_0_flashdescriptor.bin ${blobDest}/descriptor.bin || true"
                            sh "mv flashregion_2_intel_me.bin ${blobDest}/me.bin || true"
                            sh "mv flashregion_3_gbe.bin ${blobDest}/gbe.bin || true"
                            kconfigLines += [
                                "CONFIG_HAVE_IFD_BIN=y", "CONFIG_HAVE_ME_BIN=y", "CONFIG_HAVE_GBE_BIN=y",
                                "CONFIG_IFD_BIN_PATH=\"${blobDest}/descriptor.bin\"",
                                "CONFIG_ME_BIN_PATH=\"${blobDest}/me.bin\"",
                                "CONFIG_GBE_BIN_PATH=\"${blobDest}/gbe.bin\""
                            ]
                            hasIfd = true
                        } else {
                            echo "ℹ️  No stock.rom provided: building a BIOS-region-only image (no IFD/ME/GBE)."
                        }

                        // --- PAYLOAD OPTIONS ---
                        if (params.CONFIG_PAYLOAD in ['SEABIOS', 'SEAGRUB']) {
                            kconfigLines += [
                                "CONFIG_GENERIC_LINEAR_FRAMEBUFFER=y", "CONFIG_SEABIOS_VGA_COREBOOT=y",
                                "CONFIG_PXE=y", "CONFIG_PXE_ROM_ID=\"${pciId}\"", "CONFIG_BUILD_IPXE=y"
                            ]
                        } else if (params.CONFIG_PAYLOAD == 'EDK2') {
                            kconfigLines += [
                                "CONFIG_EDK2_BOOT_MANAGER_ESCAPE=y", "CONFIG_EDK2_ENABLE_IPXE=y", "CONFIG_EDK2_REPO_OFFICIAL=y",
                                "CONFIG_EDK2_DEBUG_LEVEL=0", "CONFIG_EDK2_SECURE_BOOT=n", "CONFIG_EDK2_FULL_SCREEN_SETUP=n"
                            ]
                        }

                        sh "rm -rf build .config"
                        writeFile file: '.config', text: kconfigLines.join('\n') + '\n'

                        echo "=== Compiling coreboot (silent) ==="
                        sh "make olddefconfig > build_config.log 2>&1 || { cat build_config.log; exit 1; }"
                        sh "make -s -j\$(nproc) > build_coreboot.log 2>&1 || { echo '❌ Compilation failed! Last 500 lines:'; tail -n 500 build_coreboot.log; exit 1; }"

                        // --- SAFETY GATE: ROM size must equal CONFIG_ROM_SIZE ---
                        sh '''
                            set -e
                            EXPECTED=$(printf '%d' "$(grep '^CONFIG_ROM_SIZE=' .config | cut -d= -f2)")
                            ACTUAL=$(stat -c%s build/coreboot.rom)
                            echo "ROM size: expected ${EXPECTED} bytes, got ${ACTUAL} bytes"
                            if [ "$EXPECTED" != "$ACTUAL" ]; then
                                echo "❌ ROM size mismatch -- refusing to deploy (would risk a bad flash)."
                                exit 1
                            fi
                        '''

                        def safePayloadName = params.CONFIG_PAYLOAD.toLowerCase().replaceAll(/[^a-z0-9]/, "-")
                        def shortHash = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        env.EXACT_ROM_NAME = "coreboot-${vendor}-${model}-${safePayloadName}-${CB_VERSION}-${shortHash}.rom"
                        sh "mv build/coreboot.rom 'build/${env.EXACT_ROM_NAME}'"
                        archiveArtifacts artifacts: "build/${env.EXACT_ROM_NAME}", fingerprint: true
                        stash name: 'finished-rom', includes: "build/${env.EXACT_ROM_NAME}"
                        echo "✅ Build complete: ${env.EXACT_ROM_NAME}"
                    }

                    // Hand metadata to the Deploy stage.
                    env.MODEL        = model
                    env.ROM_HAS_IFD  = hasIfd ? '1' : '0'
                    env.DMI_MATCH    = DMI_MATCH.get(model, '')
                    env.PAYLOAD      = params.CONFIG_PAYLOAD
                }
            }
        }

        stage('Deploy to Server') {
            agent { label 'built-in' }
            steps {
                echo "=== Deploying board-tagged ROM + manifest ==="
                cleanWs()
                unstash 'finished-rom'
                script {
                    // Shell runs on the controller, which has ${TFTP_COREBOOT} mounted.
                    withEnv([
                        "ROM_FILE=build/${env.EXACT_ROM_NAME}",
                        "ROM_NAME=${env.EXACT_ROM_NAME}",
                        "CB_VERSION=${CB_VERSION}",
                        "TFTP_COREBOOT=${TFTP_COREBOOT}"
                    ]) {
                        sh '''
                            set -eu
                            ROMDIR="${TFTP_COREBOOT}/roms/${MODEL}"
                            MAN="${TFTP_COREBOOT}/manifest.tsv"

                            if [ ! -w "${TFTP_COREBOOT}" ]; then
                                echo "❌ ${TFTP_COREBOOT} is not writable by uid $(id -u) (owner: $(stat -c '%u:%g' "${TFTP_COREBOOT}"))."
                                echo "   Re-run the PXE playbook: it hands this directory to the Jenkins uid."
                                exit 1
                            fi
                            mkdir -p "$ROMDIR"

                            SHA=$(sha256sum "$ROM_FILE" | cut -d' ' -f1)
                            SIZE=$(stat -c%s "$ROM_FILE")

                            # Keep a hashed copy for history, plus a stable per-board file.
                            cp -f "$ROM_FILE" "$ROMDIR/$ROM_NAME"
                            cp -f "$ROM_FILE" "$ROMDIR/coreboot.rom"

                            cat > "$ROMDIR/meta.env" <<EOF
MODEL=${MODEL}
DMI_MATCH=${DMI_MATCH}
PAYLOAD=${PAYLOAD}
CB_VERSION=${CB_VERSION}
HAS_IFD=${ROM_HAS_IFD}
ROM_SIZE=${SIZE}
ROM_SHA256=${SHA}
ROM_FILE=${ROM_NAME}
GENERATED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

                            # Rewrite the manifest line for this model (TSV, tab-separated).
                            touch "$MAN"
                            awk -F'\t' -v m="${MODEL}" '$1!=m' "$MAN" > "$MAN.tmp" || true
                            printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
                                "${MODEL}" "${DMI_MATCH}" "${SIZE}" "${SHA}" "${ROM_HAS_IFD}" "${PAYLOAD}" "${CB_VERSION}" "roms/${MODEL}/coreboot.rom" \
                                >> "$MAN.tmp"
                            mv "$MAN.tmp" "$MAN"

                            echo "✅ Published ${MODEL} (sha256 ${SHA}, ${SIZE} bytes, has_ifd=${ROM_HAS_IFD})"
                            echo "   -> $ROMDIR/coreboot.rom"
                            echo "   -> manifest $MAN"
                        '''
                    }
                }
            }
        }
    }
}

// =============================================================================
//  deguard: patched (Boot Guard-bypassing) ME + HAP-enabled IFD + GBE.
//  Runs inside the coreboot-sdk container (called from within docker.inside{}).
//  Returns the list of CONFIG_* lines to append to .config.
// =============================================================================
def prepareDeguardBlobs(String vendor, String model, Map spec) {
    echo "=== deguard: preparing Boot Guard bypass for ${model} (delta=${spec.delta}) ==="

    def cache    = "${env.WORKSPACE}/.deguard_cache"
    def blobDest = "3rdparty/blobs/mainboard/${vendor}/${model}"
    def deltaDir = "data/delta/${spec.delta}"

    sh "mkdir -p ${cache} ${blobDest}"

    // 1. deguard checkout (cached), verify the delta exists for this model.
    sh """
        if [ ! -d '${cache}/deguard/.git' ]; then
            rm -rf '${cache}/deguard'
            git clone --depth 1 '${DEGUARD_REPO}' '${cache}/deguard'
        fi
    """
    if (sh(script: "[ -d '${cache}/deguard/${deltaDir}' ]", returnStatus: true) != 0) {
        error "❌ deguard has no delta '${spec.delta}' for ${model}. Refusing to build an unbootable ROM."
    }

    // 2. IFD + GBE from the uploaded vendor dump (sklkbl layout).
    sh "./util/ifdtool/ifdtool -x -p sklkbl stock.rom > /dev/null 2>&1"
    sh "cp flashregion_0_flashdescriptor.bin ${cache}/ifd.bin"
    sh "test -s flashregion_3_gbe.bin && mv flashregion_3_gbe.bin ${blobDest}/gbe.bin"

    // 3. Enable the HAP bit in the IFD (required or the patched ME will not load
    //    and the board will NOT boot). ifdtool writes <file>.new
    sh "rm -f ${cache}/ifd.bin.new"
    sh "./util/ifdtool/ifdtool -p sklkbl -M 1 ${cache}/ifd.bin"
    sh "cp ${cache}/ifd.bin.new ${blobDest}/descriptor.bin"

    // 4. Donor ME (cached, sha256-verified), extracted from the vendor updater.
    def haveDonor = sh(script: """
        [ -f '${cache}/me_donor.bin' ] && \\
        [ "\$(sha256sum '${cache}/me_donor.bin' | cut -d' ' -f1)" = '${DONOR_LP_SHA256}' ]
    """, returnStatus: true) == 0

    if (!haveDonor) {
        echo "=== deguard: fetching + extracting donor ME ==="
        sh "rm -rf ${cache}/donor && mkdir -p ${cache}/donor"
        sh """
            cd ${cache}/donor
            ( curl -fsSL -A 'Mozilla/5.0' -o updater.exe '${DONOR_LP_URL}' \\
              || wget -q -U 'Mozilla/5.0' -O updater.exe '${DONOR_LP_URL}' )
            ( curl -fsSL -o Dell_PFS_Extract.py '${DELL_PFS_EXTRACT}' \\
              || wget -q -O Dell_PFS_Extract.py '${DELL_PFS_EXTRACT}' )
            python3 -I Dell_PFS_Extract.py updater.exe > pfs.log 2>&1 || { tail -n 40 pfs.log; exit 1; }
        """
        def found = sh(script: """
            find ${cache}/donor -type f -size ${DONOR_LP_SIZE}c 2>/dev/null | while read f; do
                if [ "\$(sha256sum \"\$f\" | cut -d' ' -f1)" = '${DONOR_LP_SHA256}' ]; then echo "\$f"; break; fi
            done
        """, returnStdout: true).trim()
        if (!found) {
            error "❌ Could not locate the verified donor ME (sha256 ${DONOR_LP_SHA256}) in the updater. The archived URL may have changed."
        }
        sh "cp '${found}' ${cache}/me_donor.bin"
        echo "✅ Donor ME verified + cached."
    } else {
        echo "✅ Using cached donor ME."
    }

    // 5. Generate the patched ME image.
    sh """
        cd ${cache}/deguard
        python3 -I finalimage.py \\
            --delta '${deltaDir}' \\
            --version '${spec.version}' \\
            --pch '${spec.pch}' \\
            --sku '${spec.sku}' \\
            --fake-fpfs data/fpfs/zero \\
            --input '${cache}/me_donor.bin' \\
            --output '${env.WORKSPACE}/${blobDest}/me.bin'
    """
    sh "[ -s '${blobDest}/me.bin' ] || { echo '❌ deguard produced an empty ME image'; exit 1; }"
    echo "✅ deguard: HAP IFD + patched ME + GBE staged in ${blobDest}"

    return [
        "CONFIG_HAVE_IFD_BIN=y", "CONFIG_HAVE_ME_BIN=y", "CONFIG_HAVE_GBE_BIN=y",
        "CONFIG_IFD_BIN_PATH=\"${blobDest}/descriptor.bin\"",
        "CONFIG_ME_BIN_PATH=\"${blobDest}/me.bin\"",
        "CONFIG_GBE_BIN_PATH=\"${blobDest}/gbe.bin\""
    ]
}
