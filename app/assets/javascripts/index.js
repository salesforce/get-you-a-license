// wtf JS?
HTMLCollection.prototype.forEach = Array.prototype.forEach;
NodeList.prototype.forEach = Array.prototype.forEach;
NodeList.prototype.reduce = Array.prototype.reduce;

document.addEventListener('DOMContentLoaded', onload, false);

function gitHubAccessToken() {
  return document.body.getAttribute("data-github-token");
}

function onload() {
  document.getElementById("license").addEventListener("change", licenseChange);

  document.getElementsByName("sendLicensePr").forEach(function(button) {
    button.addEventListener("click", function(event) {
      var paramInputs = document.getElementsByName("param");
      var json = paramInputs.reduce(function(params, input) {
        params[input.id] = input.value;
        return params;
      }, {});

      json["orgRepo"] = event.target.value;
      json["licenseKey"] = document.getElementById("license").value;

      var li = event.target.parentElement;
      var spinner = document.createElement("img");
      spinner.setAttribute("src", "/assets/lib/salesforce-lightning-design-system/assets/images/spinners/slds_spinner.gif");
      spinner.style["width"] = "16px";
      spinner.style["vertical-align"] = "top";
      li.appendChild(spinner);

      event.target.remove();

      var req = new XMLHttpRequest();
      req.onreadystatechange = function() {
        if (this.readyState === 4 && this.status === 200) {
          var pullRequest = JSON.parse(req.responseText);

          var prLink = document.createElement("a");
          prLink.setAttribute("href", pullRequest.html_url);
          prLink.innerText = "PR #" + pullRequest.number;

          spinner.remove();

          li.appendChild(prLink);
        }
      };
      req.open("POST", "/license_pull_request");
      req.setRequestHeader("X-GITHUB-TOKEN", gitHubAccessToken());
      req.setRequestHeader("Content-Type", "application/json;charset=UTF-8");
      req.send(JSON.stringify(json));
    });
  });
}

function togglePrButton(disabled) {
  document.getElementsByName("sendLicensePr").forEach(function(button) {
    button.disabled = disabled;
  });
}

function licenseChange(event) {
  var license = event.target.value;
  var req = new XMLHttpRequest();
  req.onreadystatechange = function() {
    if (this.readyState === 4 && this.status === 200) {
      var params = JSON.parse(req.responseText);
      var licenseInputs = document.getElementById("licenseInputs");
      var paramInputs = document.getElementsByName("param");

      Array.prototype.slice.call(paramInputs).forEach(function(child) {
        child.remove();
      });

      togglePrButton(params.length > 0);

      params.forEach(function(param) {
        var input = document.createElement("input");
        input.id = param;
        input.setAttribute("name", "param");
        input.setAttribute("placeholder", param);
        input.addEventListener("input", function() {
          var paramInputs = document.getElementsByName("param");
          var allParamsHaveValue = paramInputs.reduce(function(state, input) {
            return (input.value.length > 0) && state;
          }, true);
          togglePrButton(!allParamsHaveValue);
        });
        licenseInputs.appendChild(input);
      });
    }
  };
  req.open("GET", "/license_params?key=" + license, true);
  req.setRequestHeader("X-GITHUB-TOKEN", gitHubAccessToken());
  req.send();
}
