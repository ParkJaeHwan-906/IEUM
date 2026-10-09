if redis.call('SET', KEYS[1], ARGV[1], 'NX') then
    for i = 2, #ARGV do
        redis.call('SADD', KEYS[2], ARGV[i])
    end
    return 1
end
return 0
