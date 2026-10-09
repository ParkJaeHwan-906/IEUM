if not redis.call('SET', KEYS[3], '1', 'NX', 'EX', ARGV[3]) then
    return 0
end
redis.call('INCRBY', KEYS[1], ARGV[1])
redis.call('SREM', KEYS[2], ARGV[2])
return 1
