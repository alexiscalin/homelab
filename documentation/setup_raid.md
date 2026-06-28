# Guide to reproduce the raid 5 array

## Find the drives to use

To find the drives to use :

`lsblk`

I got to see that I wanted to use the drives : sda sdc sdd and sde.

## Format the drives

Once I was  100% sure about the drives to use, I then proceded to wipe them using the command : 

`sudo wipefs -a /dev/sda /dev/sdc /dev/sdd /dev/sde`

## Create the raid 5 array

For this setup I went with mdadm and latter partitionned it with ext4.

To create the array I used :

`sudo mdadm --create --verbose /dev/md0 --level=5 --raid-devices=4 /dev/sda /dev/sdc /dev/sdd /dev/sde`

The creation will take time, it lasted about 4 hours this time. TO follow the progression :

`sudo watch mdadm --detail /dev/md0`

To make sure that the OS (Debian in this case) recognize the raid array after reboot : 

`sudo mdadm --detail --scan | sudo tee -a /etc/mdadm/mdadm.conf`

and

`sudo update-initramfs -u`

To format the newly created array :

`sudo mkfs.ext4 /dev/md0`

## Closeup

If everything went well the raid should be good to go, and ready to be used as a regular drive.

You can mount it using the `mount` command and begin to copy some data on it.
