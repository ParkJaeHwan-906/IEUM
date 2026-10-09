local remaining = redis.call('GET', KEYS[1])
if not remaining then
    return 'MISSING'
end
local adjusted = tonumber(remaining) + tonumber(ARGV[1])
if adjusted < 0 then
    return 'BELOW_HELD'
end
redis.call('INCRBY', KEYS[1], ARGV[1])
return tostring(adjusted)
