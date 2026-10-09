redis.call('INCRBY', KEYS[1], ARGV[1])
redis.call('SREM', KEYS[2], ARGV[2])
redis.call('DEL', KEYS[3])
return 1
