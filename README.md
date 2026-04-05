# homelab

# Using the venv

```
python3 -m venv .venv
source .venv/bin/activate
```

# Installing the dependencies

```
pip3 install --upgrade -r requirements.txt
```

# Configure PXE Server

```
ansible-playbook -i inventories/hosts.yml playbooks/setup_pxe.yml -K
```
