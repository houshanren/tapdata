import os
import time
from random import randint
import requests
import json


def get_suffix():
    table_suffix = "_%d_%d" % (int(time.time() * 1000), randint(1, 10000))
    return table_suffix


def get_test_table(table):
    return "%s%s" % (table, get_suffix())


def get_task_name(task):
    return "%s%s" % (task, get_suffix())


class Request:
    def __init__(self, api, host=r'http://139.198.127.204:31196/', bash_path='api/'):
        self.url = host + bash_path + api

    def _res_dict(self, resp):
        if resp.status_code < 200 or resp.status_code >= 300:
            print("{}, {}".format(resp.status_code, resp.text))
            print("request failed url: {}".format(resp.url))
            return False
        else:
            json_res = resp.json()
            return json_res

    def get(self, id, **kwargs):
        res = requests.get(self.url + f'/{id}', **kwargs)
        res_data = self._res_dict(res)
        return res_data

    def put(self, id, **kwargs):
        res = requests.put(self.url + f'/{id}', **kwargs)
        res_data = self._res_dict(res)
        return res_data

    def delete(self, id, **kwargs):
        res = requests.delete(self.url + f'/{id}', **kwargs)
        res_data = self._res_dict(res)
        return res_data

    def post(self, **kwargs):
        res = requests.post(self.url, **kwargs)

    def patch(self, **kwargs):
        res = requests.patch(self.url, **kwargs)
        res_data = self._res_dict(res)
        return res_data


if __name__ == '__main__':
    # print(get_test_table("autmated_test"))
    re = Request('Task')
    res = re.get(id='64181c364365264fe06e297a?access_token=a3f045cf08db462c8617cb97cfde48b2bf7763ae11ad499da69924685fa195e5')
    print(res['data'])
