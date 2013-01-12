var reload_status = function (request, project) {
    if (request.readyState == 4) {
        if (request.status == 200) {
            location.reload(true);
        } else {
            setTimeout(function() { wait_for_boot(project); }, 2000);
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
            setTimeout(function() { watch_status(project); }, 2000);
        }
    }
};

var watch_status = function (project) {
    var request = new XMLHttpRequest();
    request.onreadystatechange = function(){ update_status(request, project); };
    request.open("GET", "/project/" + project + "/status", true);
    request.send(null);
};
