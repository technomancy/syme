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
            // TODO: update ip if halted
            // TODO: disable terminate button if halted
            // TODO: update invited list?
        }
    }
};

var watch_status = function (project) {
    var request = new XMLHttpRequest();
    request.onreadystatechange = function(){ update_status(request, project); };
    request.open("GET", "/project/" + project + "/status", true);
    request.send(null);
};

// termination

var show_terminate = function () {
    document.getElementById("terminate").style.display = 'block';
};

var hide_terminate = function () {
    document.getElementById("terminate").style.display = 'none';
    return false;
};

var terminate = function (project) {
    var request = new XMLHttpRequest();
    // TODO: indicate progress is happening
    request.onreadystatechange = function(){ };
    request.open("DELETE", "/project/" + project, true);
    request.send(null);
    hide_terminate();
};
