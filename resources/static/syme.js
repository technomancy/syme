// instance reload

var poll_interval = 4000;

var reload_status = function (request, project) {
    if (request.readyState == 4) {
        if (request.status == 200) {
            location.reload(true);
        } else {
            setTimeout(function() { wait_for_boot(project); }, poll_interval);
        }
    }
};

var wait_for_boot = function (project) {
    var request = new XMLHttpRequest();
    request.onreadystatechange = function(){ reload_status(request, project); };
    request.open("GET", "/project/" + project + "/status", true);
    request.send(null);
};

var update_status = function (request, project) {
    if (request.readyState == 4) {
        if (request.status == 200) {
            var data = JSON.parse(request.responseText);
            document.getElementById("status").innerHTML = data.status;
            document.getElementById("status").className = data.status;
            setTimeout(function() { watch_status(project); }, poll_interval);

            if(data.status == "halted" || data.status == "halting") {
                document.getElementById("haltbutton").style.display = "none";
            }
            if(data.status == "halted" || data.status == "halting" ||
               data.status == "failed") {
                document.getElementById("ip").style["text-decoration"] = "line-through";
            }
        }
    }
};

var watch_status = function (project) {
    var request = new XMLHttpRequest();
    request.onreadystatechange = function(){ update_status(request, project); };
    request.open("GET", "/project/" + project + "/status", true);
    request.send(null);
};

// halt

var show_halt = function () {
    document.getElementById("halt").style.display = 'block';
};

var hide_halt = function () {
    document.getElementById("halt").style.display = 'none';
    return false;
};

var halt = function (project) {
    var request = new XMLHttpRequest();
    // TODO: indicate progress is happening
    request.onreadystatechange = function(){ };
    request.open("DELETE", "/project/" + project, true);
    request.send(null);
    hide_halt();
};
