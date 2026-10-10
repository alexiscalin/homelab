#!/bin/sh
# =============================================================================
#  Coreboot flasher  (Alpine Linux live, busybox /bin/sh)
# -----------------------------------------------------------------------------
#  Safe, board-aware internal flashing. This tool:
#    1. detects the running machine via DMI,
#    2. pulls the publish manifest from the build server,
#    3. selects ONLY the ROM published for THIS exact board,
#    4. verifies the download's sha256 and size against the manifest,
#    5. checks the ROM fits the detected SPI chip,
#    6. defaults to a BIOS-region-only write and refuses unsafe full-chip
#       writes (no flash descriptor present) -- the things that brick boards.
#
#  NOTE: the FIRST coreboot install on a Boot Guard board must be done with an
#  EXTERNAL programmer (full chip, with the deguarded ME + HAP descriptor).
#  This tool is for safe *subsequent* internal updates.
# =============================================================================
set -u

# Build/boot server (same host that serves PXE over HTTP:80). Override with
# the COREBOOT_SERVER env var if needed.
SERVER="${COREBOOT_SERVER:-192.168.1.79}"
BASE="http://${SERVER}/coreboot"
ROM=/tmp/coreboot.rom

line() { echo "================================================="; }
die()  { echo; echo "ERREUR: $*"; echo "Lancement d'un shell de depannage..."; exec /bin/sh; }

line; echo "   COREBOOT FLASHER (Alpine, board-aware)"; line

# --- 1. Dependencies ---------------------------------------------------------
echo "[1/6] Installation des outils (flashrom, dmidecode)..."
# flashrom lives in the Alpine 'community' repo, which the netboot cmdline does
# not enable by default -- add both main and community if missing.
REL="latest-stable"
if ! grep -q "/community" /etc/apk/repositories 2>/dev/null; then
    {
        echo "https://dl-cdn.alpinelinux.org/alpine/${REL}/main"
        echo "https://dl-cdn.alpinelinux.org/alpine/${REL}/community"
    } >> /etc/apk/repositories
fi
# Retry apk update while networking/DHCP finishes coming up.
i=0; while [ $i -lt 15 ]; do apk update >/dev/null 2>&1 && break; sleep 2; i=$((i+1)); done
apk add --no-cache flashrom dmidecode >/dev/null 2>&1 \
    || die "Impossible d'installer flashrom/dmidecode (pas de reseau ou repo community indisponible ?)."

# --- 2. Identify this machine ------------------------------------------------
echo "[2/6] Identification de la carte..."
SYS_VENDOR=$(cat /sys/class/dmi/id/sys_vendor 2>/dev/null)
PRODUCT=$(cat /sys/class/dmi/id/product_version 2>/dev/null)
[ -n "$PRODUCT" ] || PRODUCT=$(dmidecode -s system-version 2>/dev/null | head -n1)
[ -n "$PRODUCT" ] || PRODUCT=$(cat /sys/class/dmi/id/product_name 2>/dev/null)
echo "   Fabricant : ${SYS_VENDOR:-inconnu}"
echo "   Modele    : ${PRODUCT:-inconnu}"
[ -n "$PRODUCT" ] || die "Impossible de lire l'identite DMI de la machine."

# --- 3. Fetch manifest, match THIS board ------------------------------------
echo "[3/6] Recuperation du manifeste depuis ${BASE}..."
MANIFEST=$(wget -qO - "${BASE}/manifest.tsv") \
    || die "Manifeste introuvable sur le serveur (${BASE}/manifest.tsv)."

# manifest columns: model  dmi_match  size  sha256  has_ifd  payload  cb_version  path
MATCH=$(printf '%s\n' "$MANIFEST" | awk -F'\t' -v p="$PRODUCT" '$2==p {print; exit}')
if [ -z "$MATCH" ]; then
    echo
    echo "   Aucune ROM publiee pour '${PRODUCT}'."
    echo "   Cartes disponibles dans le manifeste :"
    printf '%s\n' "$MANIFEST" | awk -F'\t' 'NF>=2 {printf "     - %s (%s)\n", $2, $1}'
    die "Refus de flasher: aucune image ne correspond a cette carte (anti-brick)."
fi

MODEL=$(printf '%s' "$MATCH"  | cut -f1)
EXP_SIZE=$(printf '%s' "$MATCH" | cut -f3)
EXP_SHA=$(printf '%s' "$MATCH"  | cut -f4)
HAS_IFD=$(printf '%s' "$MATCH"  | cut -f5)
PAYLOAD=$(printf '%s' "$MATCH"  | cut -f6)
CBVER=$(printf '%s' "$MATCH"    | cut -f7)
RPATH=$(printf '%s' "$MATCH"    | cut -f8)
echo "   -> ROM: ${MODEL} / payload ${PAYLOAD} / coreboot ${CBVER}"

# --- 4. Download + verify integrity -----------------------------------------
echo "[4/6] Telechargement + verification..."
wget -qO "$ROM" "${BASE}/${RPATH}" || die "Echec du telechargement de la ROM."
[ -s "$ROM" ] || die "ROM telechargee vide."
DL_SIZE=$(stat -c%s "$ROM")
DL_SHA=$(sha256sum "$ROM" | cut -d' ' -f1)
[ "$DL_SIZE" = "$EXP_SIZE" ] || die "Taille incorrecte (attendu ${EXP_SIZE}, obtenu ${DL_SIZE})."
[ "$DL_SHA"  = "$EXP_SHA"  ] || die "Empreinte sha256 incorrecte -- image corrompue."
echo "   sha256 OK (${DL_SHA})"

# --- 5. Probe the SPI chip, sanity-check sizes ------------------------------
echo "[5/6] Detection de la puce SPI..."
PROBE=$(flashrom -p internal 2>&1)
CHIP_LINE=$(printf '%s\n' "$PROBE" | grep -i "flash chip" | head -n1)
CHIP=$(printf '%s' "$CHIP_LINE" | sed -n 's/.*"\([^"]*\)".*/\1/p')
CHIP_KB=$(printf '%s' "$CHIP_LINE" | sed -n 's/.*(\([0-9]*\) kB).*/\1/p')
[ -n "$CHIP" ] || { printf '%s\n' "$PROBE"; die "Aucune puce SPI detectee par flashrom."; }
CHIP_BYTES=$(( ${CHIP_KB:-0} * 1024 ))
echo "   Puce      : $CHIP"
echo "   Capacite  : ${CHIP_KB:-?} kB"
echo "   Image     : $(( DL_SIZE / 1024 )) kB (has_ifd=${HAS_IFD})"

if [ "$CHIP_BYTES" -gt 0 ] && [ "$DL_SIZE" -gt "$CHIP_BYTES" ]; then
    die "La ROM ($DL_SIZE o) est plus grande que la puce ($CHIP_BYTES o). Refus."
fi

# --- 6. Choose write mode (safe defaults) -----------------------------------
echo
line
echo "            VERIFICATION AVANT FLASH"
line
echo "  Carte detectee  : $PRODUCT  ->  $MODEL"
echo "  Puce            : $CHIP (${CHIP_KB:-?} kB)"
echo "  Image           : $PAYLOAD / coreboot $CBVER"
line
echo " [1] Region BIOS uniquement   (--ifd -i bios)  [RECOMMANDE - mise a jour]"
if [ "$HAS_IFD" = "1" ] && [ "$CHIP_BYTES" -gt 0 ] && [ "$DL_SIZE" = "$CHIP_BYTES" ]; then
    echo " [2] Puce COMPLETE            (descripteur + ME + GBE + BIOS)"
    FULL_OK=1
else
    echo " [2] Puce complete            INDISPONIBLE (pas de descripteur dans la ROM)"
    FULL_OK=0
fi
echo " [q] Annuler"
line
printf "Votre choix (1/2/q) : "
read -r CHOICE

case "$CHOICE" in
    1)
        echo "[6/6] Flash region BIOS (NE PAS ETEINDRE)..."
        flashrom -p internal -c "$CHIP" --ifd -i bios -N -w "$ROM"
        RC=$?
        ;;
    2)
        if [ "$FULL_OK" != "1" ]; then
            die "Flash complet refuse: cette ROM n'a pas de descripteur/ME (ecrire la puce entiere la briquerait)."
        fi
        echo "ATTENTION: ecriture de la puce ENTIERE."
        printf "Taper 'OUI' pour confirmer : "
        read -r CONFIRM
        [ "$CONFIRM" = "OUI" ] || die "Annule."
        echo "[6/6] Flash COMPLET (NE PAS ETEINDRE)..."
        flashrom -p internal -c "$CHIP" -w "$ROM"
        RC=$?
        ;;
    *)
        echo "Annulation."; exec /bin/sh
        ;;
esac

if [ "${RC:-1}" -eq 0 ]; then
    line; echo "   FLASH REUSSI. Redemarrage dans 10 secondes."; line
    sleep 10; reboot
else
    line; echo "   ECHEC DU FLASH (code ${RC:-?}). Shell de depannage."; line
    exec /bin/sh
fi
