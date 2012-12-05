for i in {0..9}
do
  echo "Running test $i"
  ruby build-sink.rb $i
  ./test.sh
done