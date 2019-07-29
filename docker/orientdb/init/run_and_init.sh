#!/bin/bash
  
set -m
  
# Start the primary process and put it in the background
/orientdb/bin/dserver.sh &
  
# Start the initialization process
sleep 45
/orientdb/bin/console.sh /orientdb/init/create-db-with-schema.osql

# Bring the primary process back into the foreground
fg %1