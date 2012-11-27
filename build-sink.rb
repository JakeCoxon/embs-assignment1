
file = IO.read("src/embs/Sink.java")

sinks_choice = []

sinks_choice << [
  {:id => 1, :n => 10, :t => 500, :channel => 0, :panid => 11},
  {:id => 2, :n => 4, :t => 700, :channel => 1, :panid => 12},
  {:id => 3, :n => 5, :t => 1500, :channel => 2, :panid => 13}]

sinks_choice << [
{:id=>1, :channel=>0, :panid=>11, :n=>2, :t=>541},
{:id=>2, :channel=>1, :panid=>12, :n=>3, :t=>912},
{:id=>3, :channel=>2, :panid=>13, :n=>6, :t=>1101}]

sinks_choice << [
{:id=>1, :channel=>0, :panid=>11, :n=>6, :t=>1407},
{:id=>2, :channel=>1, :panid=>12, :n=>5, :t=>567},
{:id=>3, :channel=>2, :panid=>13, :n=>2, :t=>1207}]

sinks_choice << [
{:id=>1, :channel=>0, :panid=>11, :n=>2, :t=>725},
{:id=>2, :channel=>1, :panid=>12, :n=>8, :t=>868},
{:id=>3, :channel=>2, :panid=>13, :n=>4, :t=>1043}]

sinks_choice << [
{:id=>1, :channel=>0, :panid=>11, :n=>10, :t=>683},
{:id=>2, :channel=>1, :panid=>12, :n=>7, :t=>1308},
{:id=>3, :channel=>2, :panid=>13, :n=>2, :t=>685}]

sinks_choice << [
{:id=>1, :channel=>0, :panid=>11, :n=>1, :t=>683},
{:id=>2, :channel=>1, :panid=>12, :n=>7, :t=>1308},
{:id=>3, :channel=>2, :panid=>13, :n=>2, :t=>685}]


sinks = nil
if id = ARGV[0] and id[/^[0-9]+$/] then
	sinks = sinks_choice[id.to_i]
elsif ARGV[0] == "random" then
	sinks = (0..2).map do |i|
		{:id => i+1, :channel => i, :panid => 11 + i,      :n => rand(2..10), :t => rand(500..1500)}
	end 
else
	sinks = sinks_choice[0]
end

sinks.each do |a|
  p a
	newfile = file.dup
	newfile = newfile.gsub(/(?<=public class Sink)/, a[:id].to_s)
	newfile = newfile.gsub(/Sink\./, "Sink#{a[:id]}.")
	newfile = newfile.gsub(/(?<=private static int n = )([0-9]+)/, a[:n].to_s)
	newfile = newfile.gsub(/(?<=private static int t = )([0-9]+)/, a[:t].to_s)
	
	newfile = newfile.gsub(/(?<=private static byte channel = )([0-9]+)/, a[:channel].to_s)
	newfile = newfile.gsub(/(?<=private static byte panid = 0x)([0-9]+)/, a[:panid].to_s)
	newfile = newfile.gsub(/(?<=private static byte address = 0x)([0-9]+)/, a[:panid].to_s)
	
	
	IO.write("src/embs/Sink#{a[:id]}.java", newfile)
	system "mrc --verbose --assembly=sink#{a[:id]}-1.0 src/embs/Sink#{a[:id]}.java -r:logger-9.0"
end