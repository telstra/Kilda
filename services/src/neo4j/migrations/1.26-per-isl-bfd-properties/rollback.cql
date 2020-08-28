MATCH (s:bfd_session) REMOVE s.physical_port, s.interval, s.multiplier
MATCH (s:switch)-[l:isl]->(d:switch) REMOVE l.bfd_interval, l.bfd_multiplier
