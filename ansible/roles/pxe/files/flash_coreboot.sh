#!/bin/bash
echo "================================================="
echo "   DÉMARRAGE DE L'UTILITAIRE COREBOOT FLASHER    "
echo "================================================="

# 1. Installation de flashrom
echo "[1/4] Attente de l'initialisation de base des clés pacman..."

# On attend que le Live CD finisse de générer son trousseau de base
while ! systemctl is-active --quiet pacman-init.service; do
    sleep 1
done

echo "Mise à jour du trousseau vers la dernière version..."
# Indispensable si l'ISO a plus de quelques jours
pacman -Sy --noconfirm archlinux-keyring > /dev/null

echo "Installation de flashrom..."
# Le -S installe flashrom avec les dépendances à jour (et --overwrite gère les conflits)
pacman -S --noconfirm --overwrite '*' flashrom > /dev/null

# 2. Téléchargement de la ROM
echo "[2/4] Téléchargement de la ROM depuis le serveur HTTP..."
curl -s -o /tmp/coreboot.rom "http://192.168.1.79:80/coreboot/coreboot.rom"

if [ ! -s "/tmp/coreboot.rom" ]; then
    echo "ERREUR : Impossible de télécharger la ROM ou fichier vide."
    echo "Lancement d'un shell de dépannage..."
    /bin/bash
    exit 1
fi

# 3. Détection de la puce et de sa taille
echo "[3/4] Recherche de la puce SPI et analyse des tailles..."

# On stocke la ligne entière renvoyée par flashrom
CHIP_LINE=$(flashrom -p internal 2>&1 | grep -i "Found .* flash chip" | head -n 1)

# On extrait le nom de la puce (entre guillemets)
FIRST_CHIP=$(echo "$CHIP_LINE" | sed 's/.*"\(.*\)".*/\1/')

# On extrait la taille de la puce (ex: "8192 kB" contenu dans les parenthèses)
CHIP_SIZE=$(echo "$CHIP_LINE" | sed -n 's/.*(\([0-9]* [kM]B\).*/\1/p')

if [ -z "$CHIP_SIZE" ]; then
    CHIP_SIZE="Inconnue"
fi

if [ -z "$FIRST_CHIP" ]; then
    echo "ERREUR : Aucune puce flash SPI détectée par flashrom."
    /bin/bash
    exit 1
fi

# Calcul de la taille du fichier téléchargé en kB pour faciliter la comparaison
ROM_SIZE_BYTES=$(stat -c %s /tmp/coreboot.rom)
ROM_SIZE_KB=$((ROM_SIZE_BYTES / 1024))

echo ""
echo "================================================="
echo "             VÉRIFICATION AVANT FLASH            "
echo "================================================="
echo "  Puce détectée   : $FIRST_CHIP"
echo "  Capacité puce   : $CHIP_SIZE"
echo "-------------------------------------------------"
echo "  Fichier ROM     : coreboot.rom"
echo "  Taille fichier  : ${ROM_SIZE_KB} kB"
echo "================================================="
echo ""

# 4. Validation interactive et sélection de la région
echo "======================================================="
echo " VÉRIFICATION ET SÉLECTION DU FLASH"
echo " Les tailles correspondent-elles ?"
echo " Si oui, que voulez-vous écrire sur cette puce ?"
echo "-------------------------------------------------------"
echo " [1] Écrire la ROM COMPLÈTE"
echo " [2] Écrire UNIQUEMENT la région BIOS (--ifd -i bios)"
echo " [q] Annuler (Ne rien flasher)"
echo "======================================================="
read -p "Votre choix (1/2/q) : " FLASH_CHOICE

# Base flashrom arguments
FLASH_ARGS=("-p" "internal" "-c" "$FIRST_CHIP")

case "$FLASH_CHOICE" in
    1)
        echo "[4/4] Flashage COMPLET en cours avec -c \"$FIRST_CHIP\" (NE PAS ÉTEINDRE)..."
        FLASH_ARGS+=("-w" "/tmp/coreboot.rom")
        ;;
    2)
        echo "[4/4] Flashage de la RÉGION BIOS en cours avec -c \"$FIRST_CHIP\" (NE PAS ÉTEINDRE)..."
        FLASH_ARGS+=("--ifd" "-i" "bios" "-w" "/tmp/coreboot.rom")
        ;;
    [qQnN]*|*)
        echo "Annulation du flashage. Lancement d'un shell de dépannage..."
        /bin/bash
        exit 0 # Exits the script cleanly after the shell is closed
        ;;
esac

# Execute flashrom with the safely constructed array
flashrom "${FLASH_ARGS[@]}"

if [ $? -eq 0 ]; then
    echo "================================================="
    echo "   FLASH RÉUSSI ! Redémarrage dans 10 secondes.  "
    echo "================================================="
    sleep 10
    reboot
else
    echo "================================================="
    echo "   ÉCHEC DU FLASH. Lancement d'un shell...       "
    echo "================================================="
    /bin/bash
fi
