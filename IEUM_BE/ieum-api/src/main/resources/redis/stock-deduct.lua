local remaining = tonumber(redis.call('GET', KEYS[1]))
if remaining == nil then
    return -2
end
if remaining < tonumber(ARGV[1]) then
    return -1
end
return redis.call('DECRBY', KEYS[1], ARGV[1])
