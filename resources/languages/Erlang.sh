wget http://packages.erlang-solutions.com/erlang-solutions_1.0_all.deb
wget http://packages.erlang-solutions.com/debian/erlang_solutions.asc

sudo dpkg -i erlang-solutions_1.0_all.deb && rm erlang-solutions_1.0_all.deb
sudo apt-key add erlang_solutions.asc && rm erlang_solutions.asc

sudo apt-get update
sudo apt-get install -y erlang

wget https://github.com/rebar/rebar/wiki/rebar
chmod +x rebar
sudo mv rebar /usr/local/bin
