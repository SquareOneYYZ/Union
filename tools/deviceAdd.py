import requests
import json

imieList = [("imie", "device name"),
            ("imie", "device name")
            ]

def add_device(id, imie, strName):
    url = "https://www.server.com/api/devices"

    payload = json.dumps({
      "id": id,
      "name": strName,
      "uniqueId": imie,
      "status": "unknown",
      "disabled": "false",
      "lastUpdate": None,
      "positionId": None,
      "groupId": 12,
      "phone": None,
      "model": None,
      "contact": None,
      "category": None,
      "attributes": {}
    })
    headers = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'Authorization': 'Bearer ',
      'Cookie': 'JSESSIONID=node01ufj26g87be15lql5ei6t97wc9122.node0'
    }

    response = requests.request("POST", url, headers=headers, data=payload)

    print(response.text)
    print(response.status_code)
    return response.status_code

id = 0

for imie in imieList:

    status = add_device(id, imie[0], imie[1])
    if status == 200:
        id = id + 1
