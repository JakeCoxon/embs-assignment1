killall saguaro > /dev/null
killall mrsh > /dev/null
killall _mrsh > /dev/null

mrsh --interactive simulate.mrsh | grep "\*\*\*" &

my_PID=$!
sleep 65
kill -15 $my_PID