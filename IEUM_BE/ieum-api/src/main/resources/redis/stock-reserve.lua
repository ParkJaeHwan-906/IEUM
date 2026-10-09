local seen = redis.call('GET', KEYS[3])
if seen then
    return 'REPLAY:' .. seen
end
if redis.call('SISMEMBER', KEYS[2], ARGV[2]) == 1 then
    return 'DUPLICATE'
end
local remaining = redis.call('GET', KEYS[1])
if not remaining then
    return 'MISSING'
end
if tonumber(remaining) < tonumber(ARGV[1]) then
    return 'SOLD_OUT'
end
redis.call('DECRBY', KEYS[1], ARGV[1])
redis.call('SADD', KEYS[2], ARGV[2])
redis.call('SET', KEYS[3], 'PENDING', 'EX', ARGV[3])
return 'OK'
